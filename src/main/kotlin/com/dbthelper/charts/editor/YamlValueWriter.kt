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
        // Nothing to climb from if the target itself doesn't exist: bail out rather than delete
        // whatever unrelated key happens to sit at a shorter prefix of the path.
        BoardTargets.keyValue(root, path) ?: return@edit
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

    /**
     * `nested`'s own contract (see [YamlText]) makes its `indent` parameter the column of its
     * *second* key onward: the first key lands wherever the caller's own prefix places it. Callers
     * here always know the real column of that first key, so this converts "real column of the
     * first key" into the `indent` argument `nested` expects.
     */
    private fun nestedFrom(firstKeyColumn: Int, keys: List<String>, value: String, step: Int): String =
        YamlText.nested(keys, value, firstKeyColumn + step, step)

    private fun insertPath(document: Document, root: YAMLMapping, path: List<String>, value: String) {
        val step = CodeStyle.getIndentSize(root.containingFile).takeIf { it > 0 } ?: 2
        var mapping = root
        var depth = 0
        while (depth < path.size - 1) {
            val next = mapping.getKeyValueByKey(path[depth]) ?: break
            val nextValue = next.value
            if (nextValue is YAMLMapping) {
                mapping = nextValue
                depth++
                continue
            }
            // path[depth] already exists but isn't a mapping we can descend into: either a bare key
            // with no value yet (`style:`) or a scalar that must become a mapping to hold the rest of
            // the path. Either way we must not append a second `path[depth]:` line next to it.
            val keyColumn = column(document, next)
            val rest = path.drop(depth + 1)
            if (nextValue == null || nextValue.text.isBlank()) {
                // Bare key: insert the remaining path as its children, one step further in.
                val childColumn = keyColumn + step
                val text = nestedFrom(childColumn, rest, value, step)
                document.insertString(next.textRange.endOffset, "\n" + " ".repeat(childColumn) + text)
            } else {
                // Non-mapping value: replace the whole `key: scalar` with `key:` plus the nested rest.
                val text = nestedFrom(keyColumn, listOf(path[depth]) + rest, value, step)
                document.replaceString(next.textRange.startOffset, next.textRange.endOffset, text)
            }
            return
        }
        val anchor = mapping.keyValues.lastOrNull()
        val indent = anchor?.let { column(document, it) }
            ?: (column(document, mapping.parent ?: mapping) + step)
        val text = nestedFrom(indent, path.drop(depth), value, step)
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
            // The Design tab may hold a target resolved before an earlier edit; committing here can
            // reparse the PSI and invalidate it. Do nothing rather than act on a stale/wrong element.
            if (!root.isValid) return@runWriteCommandAction
            change(document)
            manager.commitDocument(document)
        }, file)
    }
}
