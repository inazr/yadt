package com.dbthelper.charts

import com.dbthelper.core.ExecutableLocator
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/** The user's `dct`: the configured path, else auto-detected; null if there is no such file. */
object DctExecutable {
    fun find(project: Project): Path? {
        val configured = DbtHelperSettings.getInstance(project).state.dctExecutablePath
        return ExecutableLocator.find("dct", configured)?.takeIf { Files.isRegularFile(it) }
    }
}
