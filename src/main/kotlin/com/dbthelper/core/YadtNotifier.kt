package com.dbthelper.core

import com.dbthelper.settings.DbtHelperSettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.SystemNotifications

/** Balloon notifications of the `YADT` group registered in plugin.xml. */
object YadtNotifier {
    private const val GROUP_ID = "YADT"

    fun notification(content: String, type: NotificationType): Notification =
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID).createNotification(content, type)

    fun notify(project: Project, content: String, type: NotificationType) =
        notification(content, type).notify(project)

    /** [notify], plus a native OS notification when enabled in settings — for dbt command outcomes. */
    fun notifyWithSystem(project: Project, content: String, type: NotificationType) {
        notify(project, content, type)
        if (DbtHelperSettings.getInstance(project).state.enableSystemNotifications) {
            val title = if (type == NotificationType.ERROR) "dbt Error" else "YADT"
            SystemNotifications.getInstance().notify("yadt", title, content)
        }
    }

    fun notifyManifestNotLoaded(project: Project) = notify(
        project,
        "dbt manifest not loaded. Run 'dbt parse' or 'dbt compile' to generate target/manifest.json.",
        NotificationType.WARNING,
    )
}
