package com.dbthelper.charts

import java.nio.file.Files
import java.nio.file.Path

/**
 * dct has no command that prints its schema; the schemas ship as package data inside the venv dct
 * was installed into. This finds that venv from the `dct` executable, covering uv/pipx symlinks
 * (Unix), plain script copies (shebang), and uv's copied `.exe` trampolines (Windows, via `uv tool dir`).
 */
object DctVenvLocator {
    private const val SCHEMA_REL = "dbt_charts/data/schemas/yaml"

    fun venvCandidates(dct: Path, uvToolDir: Path?, home: Path): List<Path> {
        val candidates = mutableListOf<Path>()
        runCatching { dct.toRealPath() }.getOrNull()?.parent?.parent?.let(candidates::add)
        // Don't realpath the interpreter: that jumps from <venv>/bin/python to the base Python.
        shebangInterpreter(dct)?.parent?.parent?.let(candidates::add)
        uvToolDir?.resolve("dbt-charts")?.let(candidates::add)
        candidates.add(home.resolve(".local/pipx/venvs/dbt-charts"))
        return candidates.distinct()
    }

    fun shebangInterpreter(script: Path): Path? {
        val head = runCatching { Files.newInputStream(script).use { it.readNBytes(512) } }.getOrNull() ?: return null
        val firstLine = String(head, Charsets.ISO_8859_1).lineSequence().firstOrNull() ?: return null
        if (!firstLine.startsWith("#!")) return null
        val interpreter = firstLine.removePrefix("#!").trim().substringBefore(' ')
        return interpreter.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }
    }

    fun schemaDir(venv: Path): Path? {
        val windows = venv.resolve("Lib/site-packages").resolve(SCHEMA_REL)
        if (Files.isDirectory(windows)) return windows
        val lib = venv.resolve("lib")
        if (!Files.isDirectory(lib)) return null
        return Files.list(lib).use { pythons ->
            pythons.filter { it.fileName.toString().startsWith("python") }
                .map { it.resolve("site-packages").resolve(SCHEMA_REL) }
                .filter { Files.isDirectory(it) }
                .findFirst().orElse(null)
        }
    }

    fun findSchemaDir(candidates: List<Path>): Path? =
        candidates.asSequence()
            .filter { Files.isRegularFile(it.resolve("pyvenv.cfg")) }
            .mapNotNull(::schemaDir)
            .firstOrNull()
}
