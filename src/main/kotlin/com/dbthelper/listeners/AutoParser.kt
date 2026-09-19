package com.dbthelper.listeners

import com.dbthelper.actions.DbtCommandRunner
import com.dbthelper.actions.DbtEngine
import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.DbtRunState
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

/**
 * Runs `dbt parse` in the background, with the target selected in YADT, so target/manifest.json
 * regenerates and lineage/code-intel stay current. The resulting manifest write is picked up by
 * ManifestFileWatcher. Triggered by [AutoParseOnSaveListener] (file saves) and
 * [AutoParseOnTargetChange] (a new target makes every relation in the manifest stale).
 *
 * Debounced; never runs while a foreground Runner command is active; single-flight; silent on
 * failure (no notification spam). Does nothing while "Auto-parse on save" is off.
 */
@Service(Service.Level.PROJECT)
class AutoParser(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(AutoParser::class.java)
    private val parsing = AtomicBoolean(false)
    @Volatile private var rearm = false

    private val debounce = Timer(1500) { _ -> maybeParse() }.apply { isRepeats = false }

    /** Schedules a parse; repeated requests within the debounce window collapse into one. */
    fun request() {
        if (!DbtHelperSettings.getInstance(project).state.autoParseOnSave) return
        debounce.restart()
    }

    private fun maybeParse() {
        val settings = DbtHelperSettings.getInstance(project)
        if (!settings.state.autoParseOnSave) return

        // detectEngine() shells out to `dbt --version` — for the dbt Cloud CLI that
        // is a network round-trip. The Timer's ActionListener fires on the EDT, so
        // hop to a pooled thread before doing any blocking I/O.
        ApplicationManager.getApplication().executeOnPooledThread {
            val runner = DbtCommandRunner(project)
            if (runner.detectEngine() == DbtEngine.CLOUD_CLI && !settings.state.autoParseOnCloudCli) return@executeOnPooledThread

            if (DbtRunState.getInstance(project).isRunning()) return@executeOnPooledThread // don't fight a manual run

            if (!parsing.compareAndSet(false, true)) { rearm = true; return@executeOnPooledThread } // single-flight

            val root = DbtProjectLocator.getInstance(project).findProjectRoot()?.path
            if (root == null) { parsing.set(false); return@executeOnPooledThread }
            val exe = runner.findDbtExecutable()

            runner.runCommand(parseCommand(exe, settings.state.activeTarget), File(root), object : DbtCommandRunner.OutputListener {
                override fun onLine(line: String) {} // silent — do not touch the Runner log
                override fun onFinished(result: DbtCommandRunner.CommandResult) {
                    if (!result.success) {
                        logger.debug("auto dbt parse failed (exit ${result.exitCode}); keeping last good manifest")
                    }
                    parsing.set(false)
                    // Timer.restart() delegates to EventQueue.invokeLater, safe off-EDT.
                    if (rearm) { rearm = false; debounce.restart() }
                }
            })
        }
    }

    override fun dispose() = debounce.stop()

    companion object {
        fun getInstance(project: Project): AutoParser = project.service()

        /** Parses with the dbt target selected in YADT, so the manifest's relations match it. */
        fun parseCommand(exe: String, target: String): List<String> =
            listOf(exe, "parse") + if (target.isBlank()) emptyList() else listOf("--target", target)
    }
}
