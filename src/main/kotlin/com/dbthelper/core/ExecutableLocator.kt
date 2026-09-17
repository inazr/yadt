package com.dbthelper.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds a CLI (dbt, dct, uv) the way a terminal would, although an IDE launched from the Dock
 * on macOS only inherits a minimal PATH: an explicit setting wins, then [preferred] locations
 * (e.g. a project venv), the usual user install dirs, and finally PATH.
 */
object ExecutableLocator {

    /**
     * [configured] is the settings value; blank or the bare [name] means "auto-detect". A
     * configured path is returned as-is — callers decide whether a missing file is an error.
     */
    fun find(name: String, configured: String = "", preferred: List<Path> = emptyList()): Path? {
        configured.trim().takeIf { it.isNotEmpty() && it != name }?.let { return Path.of(it) }
        val home = Path.of(System.getProperty("user.home"))
        val installDirs = listOf(home.resolve(".local/bin"), Path.of("/usr/local/bin"), Path.of("/opt/homebrew/bin"))
        return (preferred + installDirs.map { it.resolve(name) }).firstOrNull(::isExecutableFile)
            ?: findOnPath(name, System.getenv("PATH"), windowsExtensions())
    }

    /**
     * The first executable [name] in the directories of [pathValue]. With [extensions] (Windows),
     * only `name + extension` is tried, as a shell would. Hand-rolled because the platform's
     * PathEnvironmentVariableUtil lookups are deprecated for removal in 2026.3, while their
     * replacement doesn't exist before it.
     */
    internal fun findOnPath(name: String, pathValue: String?, extensions: List<String>): Path? {
        val fileNames = if (extensions.isEmpty()) listOf(name) else extensions.map { name + it }
        return pathValue.orEmpty().split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .flatMap { dir -> fileNames.map { runCatching { Path.of(dir, it) }.getOrNull() } }
            .firstOrNull { it != null && isExecutableFile(it) }
    }

    /** Executable extensions from PATHEXT on Windows (e.g. `.exe`); empty elsewhere. */
    private fun windowsExtensions(): List<String> {
        if (!System.getProperty("os.name").startsWith("Windows")) return emptyList()
        return (System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD")
            .split(';').filter { it.isNotBlank() }.map { it.lowercase() }
    }

    private fun isExecutableFile(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)
}
