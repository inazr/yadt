package com.dbthelper.actions

import com.dbthelper.core.YadtNotifier
import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction

class ToggleRefRelationAction : AnAction("Convert: ref ↔ Relation") {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabledAndVisible =
            editor != null && project != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        if (!editor.selectionModel.hasSelection()) return
        val text = editor.selectionModel.selectedText ?: return

        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        if (index === ManifestIndex.EMPTY) {
            YadtNotifier.notifyManifestNotLoaded(project)
            return
        }

        val replaced = if (CopyWithRefsReplacedAction.containsJinjaRefs(text)) {
            val currentFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val currentModelId = currentFile?.let { service.findCurrentModelId(it) }
            CopyWithRefsReplacedAction.replaceRefsWithRelations(text, index, currentModelId, includeDatabase = false)
        } else {
            PasteAsRefsAction.replaceRelationsWithRefs(text, index)
        }

        if (replaced == text) {
            YadtNotifier.notify(project, "Nothing to convert in the selection.", NotificationType.WARNING)
            return
        }

        WriteCommandAction.runWriteCommandAction(project, "Convert ref / Relation", null, {
            val start = editor.selectionModel.selectionStart
            val end = editor.selectionModel.selectionEnd
            editor.document.replaceString(start, end, replaced)
            editor.caretModel.moveToOffset(start + replaced.length)
        })
    }
}
