package com.dbthelper.charts

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DctVenvLocatorTest {

    @TempDir
    lateinit var tmp: Path

    private fun venv(rel: String, sitePackages: String): Path {
        val venv = tmp.resolve(rel)
        Files.createDirectories(venv.resolve(sitePackages).resolve("dbt_charts/data/schemas/yaml"))
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr/bin")
        return venv
    }

    @Test
    fun `symlinked dct resolves to its venv first`() {
        val venv = venv("uv/tools/dbt-charts", "lib/python3.12/site-packages")
        val real = Files.createDirectories(venv.resolve("bin")).resolve("dct").also { Files.writeString(it, "#!${venv}/bin/python\n") }
        val link = Files.createDirectories(tmp.resolve("home/.local/bin")).resolve("dct")
        Files.createSymbolicLink(link, real)

        val candidates = DctVenvLocator.venvCandidates(link, null, tmp.resolve("home"))

        assertEquals(venv.toRealPath(), candidates.first())
    }

    @Test
    fun `shebang interpreter names the venv when dct is a plain copy`() {
        val script = Files.createDirectories(tmp.resolve("bin")).resolve("dct")
        Files.writeString(script, "#!/opt/venvs/charts/bin/python -E\nimport sys\n")
        assertEquals(Path.of("/opt/venvs/charts/bin/python"), DctVenvLocator.shebangInterpreter(script))
        assertTrue(Path.of("/opt/venvs/charts") in DctVenvLocator.venvCandidates(script, null, tmp))
    }

    @Test
    fun `uv tool dir and pipx are fallback candidates`() {
        val script = Files.createDirectories(tmp.resolve("bin")).resolve("dct.exe").also { Files.write(it, byteArrayOf(0x4D, 0x5A)) }
        val candidates = DctVenvLocator.venvCandidates(script, tmp.resolve("uvtools"), tmp.resolve("home"))
        assertTrue(tmp.resolve("uvtools/dbt-charts") in candidates)
        assertEquals(tmp.resolve("home/.local/pipx/venvs/dbt-charts"), candidates.last())
    }

    @Test
    fun `schema dir is found under unix lib and windows Lib layouts`() {
        val unix = venv("unix", "lib/python3.12/site-packages")
        val win = venv("win", "Lib/site-packages")
        assertEquals(unix.resolve("lib/python3.12/site-packages/dbt_charts/data/schemas/yaml"), DctVenvLocator.schemaDir(unix))
        assertTrue(DctVenvLocator.schemaDir(win)!!.endsWith("site-packages/dbt_charts/data/schemas/yaml"))
    }

    @Test
    fun `findSchemaDir skips non-venvs and returns the first usable candidate`() {
        val notVenv = Files.createDirectories(tmp.resolve("usr"))
        val good = venv("good", "lib/python3.11/site-packages")
        assertEquals(DctVenvLocator.schemaDir(good), DctVenvLocator.findSchemaDir(listOf(notVenv, tmp.resolve("missing"), good)))
        assertNull(DctVenvLocator.findSchemaDir(listOf(notVenv)))
    }
}
