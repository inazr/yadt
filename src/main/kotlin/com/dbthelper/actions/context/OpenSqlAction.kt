package com.dbthelper.actions.context

import com.dbthelper.toolwindow.LineageTab
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class OpenSqlAction(
    private val lineageTab: LineageTab,
    private val nodeId: String
) : AnAction("Open SQL") {
    override fun actionPerformed(e: AnActionEvent) {
        lineageTab.openFileForNode(nodeId, preferYaml = false)
    }
}
