package com.dbthelper.charts.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.application.options.CodeStyle
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Writes Design edits into the board document as text (so comments and formatting elsewhere stay
 * untouched), one undoable command per edit. PSI only locates; the document is the source of truth.
 */
object YamlValueWriter {

    private const val COMMAND = "dbt Charts design"

    fun set(project: Project, root: YAMLMapping, path: List<String>, value: String) = edit(project, root) { document ->
        val existing = BoardTargets.keyValue(root, path)
        val existingValue = existing?.value
        when {
            existingValue != null -> document.replaceString(existingValue.textRange.startOffset, existingValue.textRange.endOffset, YamlText.scalar(value))
            existing != null -> document.insertString(existing.textRange.endOffset, " " + YamlText.scalar(value))
            else -> insertPath(document, root, path, value)
        }
    }

    fun remove(project: Project, root: YAMLMapping, path: List<String>) = edit(project, root) { document ->
        var keys = path
        // Remove the deepest key; if its mapping ends up empty, remove the parent key instead.
        while (keys.size > 1) {
            val parent = BoardTargets.keyValue(root, keys.dropLast(1))?.value as? YAMLMapping ?: break
            if (parent.keyValues.size > 1) break
            keys = keys.dropLast(1)
        }
        val keyValue = BoardTargets.keyValue(root, keys) ?: return@edit
        deleteLines(document, keyValue)
    }

    private fun insertPath(document: Document, root: YAMLMapping, path: List<String>, value: String) {
        var mapping = root
        var depth = 0
        while (depth < path.size - 1) {
            mapping = mapping.getKeyValueByKey(path[depth])?.value as? YAMLMapping ?: break
            depth++
        }
        val step = CodeStyle.getIndentSize(root.containingFile).takeIf { it > 0 } ?: 2
        val anchor = mapping.keyValues.lastOrNull()
        val indent = anchor?.let { column(document, it) }
            ?: (column(document, mapping.parent ?: mapping) + step)
        val text = YamlText.nested(path.drop(depth), value, indent, step)
        val insertAt = (anchor ?: mapping).textRange.endOffset
        document.insertString(insertAt, "\n" + " ".repeat(indent) + text)
    }

    private fun deleteLines(document: Document, element: PsiElement) {
        val startLine = document.getLineNumber(element.textRange.startOffset)
        val endLine = document.getLineNumber(element.textRange.endOffset)
        val start = document.getLineStartOffset(startLine)
        val end = if (endLine + 1 < document.lineCount) document.getLineStartOffset(endLine + 1) else document.textLength
        document.deleteString(start, end)
    }

    private fun column(document: Document, element: PsiElement): Int {
        val offset = element.textRange.startOffset
        return offset - document.getLineStartOffset(document.getLineNumber(offset))
    }

    private fun edit(project: Project, root: YAMLMapping, change: (Document) -> Unit) {
        val file = root.containingFile
        val manager = PsiDocumentManager.getInstance(project)
        val document = manager.getDocument(file) ?: return
        WriteCommandAction.runWriteCommandAction(project, COMMAND, null, {
            manager.commitDocument(document)
            change(document)
            manager.commitDocument(document)
        }, file)
    }
}
