package com.dbthelper.actions

import com.dbthelper.core.YadtNotifier
import com.dbthelper.codeintel.findSource
import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

class CopyWithRefsReplacedAction : AnAction("Copy for Target DB") {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabledAndVisible = editor != null && project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return

        val selectionModel = editor.selectionModel
        val text = if (selectionModel.hasSelection()) {
            selectionModel.selectedText ?: return
        } else {
            editor.document.text
        }

        val index = ManifestService.getInstance(project).getIndex()
        if (index === ManifestIndex.EMPTY) {
            YadtNotifier.notifyManifestNotLoaded(project)
            return
        }

        // Resolve current model for {{ this }} replacement
        val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val currentModelId = currentFile?.let { ManifestService.getInstance(project).findCurrentModelId(it) }

        val replaced = replaceRefsWithRelations(text, index, currentModelId)
        CopyPasteManager.getInstance().setContents(StringSelection(replaced))
    }

    companion object {
        // Matches {{ ref('model') }} including surrounding braces and whitespace
        private val JINJA_REF = Regex("""\{\{-?\s*ref\s*\(\s*['"]([^'"]+)['"]\s*\)\s*-?\}\}""")
        // Matches {{ source('src', 'table') }} including surrounding braces
        private val JINJA_SOURCE = Regex("""\{\{-?\s*source\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*\)\s*-?\}\}""")
        // Matches {{ this }}
        private val JINJA_THIS = Regex("""\{\{-?\s*this\s*-?\}\}""")

        /** True if [text] contains any {{ ref(…) }}, {{ source(…) }} or {{ this }} token. */
        fun containsJinjaRefs(text: String): Boolean =
            JINJA_REF.containsMatchIn(text) ||
                JINJA_SOURCE.containsMatchIn(text) ||
                JINJA_THIS.containsMatchIn(text)

        fun replaceRefsWithRelations(
            text: String,
            index: ManifestIndex,
            currentModelId: String? = null,
            includeDatabase: Boolean = true,
        ): String {
            var result = text

            // Replace {{ this }} → [database.]schema.table of current model
            if (currentModelId != null) {
                val currentNode = index.nodes[currentModelId]
                if (currentNode != null) {
                    result = JINJA_THIS.replace(result, currentNode.qualifiedName(includeDatabase))
                }
            }

            // Replace {{ source('src', 'table') }} → [database.]schema.table
            result = JINJA_SOURCE.replace(result) { match ->
                val srcName = match.groupValues[1]
                val tblName = match.groupValues[2]
                index.findSource(srcName, tblName)?.qualifiedName(includeDatabase) ?: match.value
            }

            // Replace {{ ref('model') }} → [database.]schema.table
            result = JINJA_REF.replace(result) { match ->
                val modelName = match.groupValues[1]
                index.nodes.values.firstOrNull { it.name == modelName }?.qualifiedName(includeDatabase) ?: match.value
            }

            // Comment out remaining Jinja blocks: {% ... %} and {{ config(...) }} → /* ... */
            val jinjaBlock = Regex("""\{[%{]-?.*?-?[%}]\}""", RegexOption.DOT_MATCHES_ALL)
            result = jinjaBlock.replace(result) { "/* ${it.value} */" }

            return result.trim()
        }
    }
}
