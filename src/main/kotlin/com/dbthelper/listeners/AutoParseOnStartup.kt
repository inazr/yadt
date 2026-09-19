package com.dbthelper.listeners

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Parses once when the project opens: the existing manifest may come from another target (the
 * manifest doesn't record which), and [AutoParseOnTargetChange] only fires on a change.
 */
class AutoParseOnStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Finding the dbt root uses FilenameIndex, which isn't available while indexing.
        DumbService.getInstance(project).runWhenSmart { AutoParser.getInstance(project).request() }
    }
}
