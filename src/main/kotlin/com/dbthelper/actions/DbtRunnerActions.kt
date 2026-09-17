package com.dbthelper.actions

import com.dbthelper.toolwindow.DbtToolWindowFactory
import com.dbthelper.core.DbtRunnerGateway
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Activate the YADT tool window (without stealing keyboard focus from the editor)
 * so its panel exists and has registered with the gateway, then run [andThen].
 * Covers the case where the tool window has never been opened — content creation
 * registers the handlers, and the activate callback runs afterwards.
 */
private fun activateThen(project: Project, andThen: () -> Unit) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(DbtToolWindowFactory.ID)
    if (toolWindow == null) { andThen(); return }
    toolWindow.activate({ andThen() }, false)
}

/** Run the configured dbt command, or stop the running one — same as the RUN button. */
class RunDbtCommandAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        activateThen(project) { DbtRunnerGateway.getInstance(project).run() }
    }
}

/** Clear the YADT Runner output log — same as the Clear button. */
class ClearDbtOutputAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        activateThen(project) { DbtRunnerGateway.getInstance(project).clear() }
    }
}
