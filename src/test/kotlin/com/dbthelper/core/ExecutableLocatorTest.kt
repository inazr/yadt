package com.dbthelper.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ExecutableLocatorTest {

    private fun file(dir: Path, name: String, executable: Boolean = true): Path =
        dir.resolve(name).toFile().apply { writeText(""); setExecutable(executable) }.toPath()

    private fun path(vararg dirs: Path) = dirs.joinToString(File.pathSeparator)

    @Test
    fun `first executable match in PATH order wins`(@TempDir a: Path, @TempDir b: Path, @TempDir c: Path) {
        file(a, "dbt", executable = false)
        val expected = file(b, "dbt")
        file(c, "dbt")
        assertEquals(expected, ExecutableLocator.findOnPath("dbt", path(a, b, c), emptyList()))
    }

    @Test
    fun `with extensions only the extended names are tried`(@TempDir a: Path, @TempDir b: Path) {
        file(a, "dbt")
        val expected = file(b, "dbt.exe")
        assertEquals(expected, ExecutableLocator.findOnPath("dbt", path(a, b), listOf(".com", ".exe")))
    }

    @Test
    fun `missing or empty PATH finds nothing`(@TempDir a: Path) {
        assertNull(ExecutableLocator.findOnPath("dbt", path(a), emptyList()))
        assertNull(ExecutableLocator.findOnPath("dbt", null, emptyList()))
    }
}
