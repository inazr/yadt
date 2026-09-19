package com.dbthelper.listeners

import com.dbthelper.charts.DbtChartsBoardLocator
import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.toUnixPath
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Asks [AutoParser] for a `dbt parse` when a project-owned .sql/.yml file is saved.
 * Registered as a <projectListeners> BulkFileListener (same as ManifestFileWatcher).
 */
class AutoParseOnSaveListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (!DbtHelperSettings.getInstance(project).state.autoParseOnSave) return
        val root = DbtProjectLocator.getInstance(project).findProjectRoot()?.path ?: return
        // dbt Charts boards aren't dbt resources, and the board preview saves them on every typing
        // pause: a parse per pause would rewrite manifest.json while dct is reading it.
        val relevant = events.any { event ->
            event is VFileContentChangeEvent && isRelevant(event.path, root) &&
                !DbtChartsBoardLocator.isBoardFile(event.file)
        }
        if (relevant) AutoParser.getInstance(project).request()
    }

    private fun isRelevant(path: String, root: String): Boolean {
        val norm = path.toUnixPath()
        if (!norm.startsWith(root.toUnixPath())) return false
        if ("/target/" in norm) return false // dbt's own outputs — never trigger on these
        return norm.endsWith(".sql") || norm.endsWith(".yml")
    }
}
