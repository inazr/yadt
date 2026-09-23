package com.dbthelper.charts.query

import com.dbthelper.charts.DbtChartsBoardLocator
import com.dbthelper.toolwindow.DbtToolWindowFactory
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * A run icon on each entry of a board's top-level `queries:` mapping; a click shows the query's rows
 * in the Query tab. The marker sits on the key's leaf token, as the platform asks of line markers.
 */
class BoardQueryLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null
        val entry = element.parent as? YAMLKeyValue ?: return null
        if (entry.key != element || !isQueryEntry(entry)) return null
        val board = element.containingFile?.virtualFile ?: return null
        if (!DbtChartsBoardLocator.isBoardFile(board)) return null
        val queryName = entry.keyText

        val onClick = GutterIconNavigationHandler<PsiElement> { _, elt ->
            val project = elt.project
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(DbtToolWindowFactory.ID)
                ?: return@GutterIconNavigationHandler
            toolWindow.activate { DbtQueryPreviewGateway.getInstance(project).preview(board, queryName) }
        }
        return LineMarkerInfo(
            element, element.textRange, AllIcons.RunConfigurations.TestState.Run,
            { TOOLTIP }, onClick, GutterIconRenderer.Alignment.CENTER, { TOOLTIP },
        )
    }

    /** `entry` sits in the mapping under the document's top-level `queries:` key. */
    private fun isQueryEntry(entry: YAMLKeyValue): Boolean {
        val queries = entry.parentMapping?.parent as? YAMLKeyValue ?: return false
        return queries.keyText == "queries" && queries.parentMapping?.parent is YAMLDocument
    }

    private companion object {
        const val TOOLTIP = "Preview rows (dct query)"
    }
}
