package com.dbthelper.charts

import com.dbthelper.core.YadtNotifier
import com.dbthelper.settings.DbtHelperConfigurable
import com.dbthelper.settings.SettingsChangeListener
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

class DbtChartsSchemaStarter : ProjectActivity {
    override suspend fun execute(project: Project) {
        val resolver = DctSchemaResolver.getInstance(project)
        project.messageBus.connect(resolver.cs).subscribe(SettingsChangeListener.TOPIC, object : SettingsChangeListener {
            override fun onSettingsChanged() {
                resolver.refresh()
            }
        })
        resolver.refresh().join()
        // Runs once per project open, so the notification appears at most once per session.
        if (resolver.schemaPath == null && hasDbtChartsProject(project)) notifyMissingSchema(project)
    }

    private suspend fun hasDbtChartsProject(project: Project): Boolean =
        smartReadAction(project) {
            FilenameIndex.getVirtualFilesByName(DbtChartsBoardLocator.PROJECT_FILE, GlobalSearchScope.projectScope(project))
                .isNotEmpty()
        }

    private fun notifyMissingSchema(project: Project) {
        YadtNotifier.notification(
                "dbt Charts editing support needs a schema. Install dct (uv tool install dbt-charts) " +
                    "or enable schema download in YADT settings.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Open YADT settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, DbtHelperConfigurable::class.java)
            })
            .notify(project)
    }
}
