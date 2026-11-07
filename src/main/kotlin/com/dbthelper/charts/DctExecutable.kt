package com.dbthelper.charts

import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.ExecutableLocator
import com.dbthelper.core.ProjectVenvs
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

/**
 * The user's `dct`: the configured path, else the dbt project's own venv (like dbt), else
 * auto-detected; null if there is no such file. A global `dct` (e.g. a uv tool) can be older than
 * the project's pinned dbt-charts and reject valid boards. Venvs count only in trusted projects.
 */
object DctExecutable {
    fun find(project: Project): Path? {
        val configured = DbtHelperSettings.getInstance(project).state.dctExecutablePath
        val venvs = ProjectVenvs.find(project, projectRoots(project), "dct")
        return ExecutableLocator.find("dct", configured, venvs)?.takeIf { Files.isRegularFile(it) }
    }

    /**
     * The dbt root, then the IDE project dir. The dbt root comes from the file index, which throws
     * while indexing (the schema resolver already runs at startup), so it is skipped then; the
     * project dir needs no index and is where PyCharm and `uv` put a project's `.venv`.
     */
    private fun projectRoots(project: Project): List<Path> {
        val dbtRoot = if (DumbService.isDumb(project)) null else DbtProjectLocator.getInstance(project).findProjectRoot()?.path
        return listOfNotNull(dbtRoot, project.basePath).map { Path.of(it) }
    }
}
