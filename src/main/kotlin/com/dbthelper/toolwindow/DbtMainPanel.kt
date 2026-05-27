package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandRunner
import com.dbthelper.actions.DbtCommandSpec
import com.dbthelper.actions.DbtVerb
import com.dbthelper.core.ManifestService
import com.dbthelper.core.ManifestUpdateListener
import com.dbthelper.core.model.ManifestIndex
import com.dbthelper.listeners.CurrentModelListener
import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.settings.SettingsChangeListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Hosts the shared action bar above the Lineage + Runner tabs and coordinates
 * between them: runs commands, owns run-state, auto-fills the selector from the
 * editor, and drives the lineage graph from plain selectors.
 */
class DbtMainPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val actionBar = DbtActionBar(project)
    private val tabs = JBTabbedPane()
    private val lineageTab = LineageTab(project, this)
    private val runnerTab = DbtRunnerTab(project, this)

    @Volatile private var currentProcess: Process? = null
    @Volatile private var isRunning = false

    private val plainNameRegex = Regex("^[A-Za-z0-9_]+$")

    init {
        Disposer.register(parentDisposable, this)

        tabs.addTab("Lineage", lineageTab)
        tabs.addTab("Runner", runnerTab)
        add(actionBar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        actionBar.onGo = { spec -> startCommand(spec) }
        actionBar.onStop = { stopCommand() }
        actionBar.onClear = { runnerTab.clear() }
        actionBar.onSelectorChanged = { sel -> driveLineage(sel) }

        val connection = project.messageBus.connect(this)
        connection.subscribe(CurrentModelListener.TOPIC, object : CurrentModelListener {
            override fun onCurrentModelChanged(file: VirtualFile) {
                ApplicationManager.getApplication().invokeLater { autoFillFromFile(file) }
            }
        })
        connection.subscribe(SettingsChangeListener.TOPIC, object : SettingsChangeListener {
            override fun onSettingsChanged() {
                ApplicationManager.getApplication().invokeLater { actionBar.refreshTargets() }
            }
        })
        connection.subscribe(ManifestUpdateListener.TOPIC, object : ManifestUpdateListener {
            override fun onManifestUpdated(index: ManifestIndex) {
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                        ?.let { autoFillFromFile(it) }
                }
            }
        })

        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let { autoFillFromFile(it) }
    }

    private fun autoFillFromFile(file: VirtualFile) {
        val service = ManifestService.getInstance(project)
        val modelId = service.findCurrentModelId(file) ?: return
        val node = service.getIndex().nodes[modelId] ?: return
        actionBar.setSelector(node.name)
        actionBar.setFullRefreshAvailable(node.config["materialized"] == "incremental")
    }

    private fun driveLineage(selector: String) {
        if (plainNameRegex.matches(selector)) lineageTab.focusModel(selector)
    }

    private fun startCommand(spec: DbtCommandSpec) {
        if (isRunning) return
        isRunning = true
        actionBar.setRunning(true)
        tabs.selectedComponent = runnerTab
        runnerTab.clear()

        val runner = DbtCommandRunner(project)
        runner.run(spec, object : DbtCommandRunner.OutputListener {
            override fun onProcessStarted(process: Process) { currentProcess = process }

            override fun onLine(line: String) {
                if (spec.verb == DbtVerb.PREVIEW) {
                    if (line.startsWith("$") || line.startsWith("Previewing") ||
                        line.matches(Regex("^\\d{2}:\\d{2}:\\d{2}.*"))
                    ) runnerTab.appendLine(line)
                } else {
                    runnerTab.appendLine(line)
                }
            }

            override fun onFinished(result: DbtCommandRunner.RunResult) {
                ApplicationManager.getApplication().invokeLater {
                    isRunning = false
                    currentProcess = null
                    actionBar.setRunning(false)

                    if (spec.verb == DbtVerb.PREVIEW && result.success) {
                        val table = runnerTab.formatPreviewTable(result.output)
                        runnerTab.appendLine("\n" + (table ?: "(no data returned)"))
                    }

                    val label = "dbt ${spec.verb.display.lowercase()}"
                    if (result.success) {
                        notify("$label completed", NotificationType.INFORMATION)
                    } else if (result.exitCode != -1) {
                        notify("$label failed (exit code ${result.exitCode})", NotificationType.ERROR)
                    }
                }
            }
        })
    }

    private fun stopCommand() {
        currentProcess?.destroyForcibly()
        currentProcess = null
        runnerTab.appendLine("\n--- Process terminated ---")
        isRunning = false
        actionBar.setRunning(false)
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dbt Helper")
            .createNotification(content, type)
            .notify(project)
        if (DbtHelperSettings.getInstance(project).state.enableSystemNotifications) {
            val title = if (type == NotificationType.ERROR) "dbt Error" else "dbt Helper"
            com.intellij.ui.SystemNotifications.getInstance().notify("dbt-helper", title, content)
        }
    }

    override fun dispose() {}
}
