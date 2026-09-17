package com.dbthelper.core

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
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
        return (preferred + installDirs.map { it.resolve(name) })
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?: PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(name)?.toPath()
    }
}
