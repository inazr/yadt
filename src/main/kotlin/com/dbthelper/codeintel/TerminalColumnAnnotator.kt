package com.dbthelper.codeintel

import com.dbthelper.core.ManifestService
import com.dbthelper.core.TerminalColumnAnalyzer
import com.dbthelper.core.model.ManifestIndex
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

/**
 * Best-effort location of where [column] is produced in a model's SQL.
 * Prefers an `as <col>` alias (last occurrence — the final SELECT tends to be
 * later in the text); else a bare, unqualified identifier token. Returns null
 * for columns not present in the text (e.g. a `select *` projection) or only
 * present as a table-qualified reference like `t.col`.
 */
internal fun locateColumnInProjection(sql: String, column: String): IntRange? {
    val esc = Regex.escape(column)
    Regex("""\bas\s+"?($esc)"?\b""", RegexOption.IGNORE_CASE).findAll(sql).lastOrNull()
        ?.let { return it.groups[1]!!.range }
    Regex("""(?<![\w.\"])($esc)(?![\w.\"])""", RegexOption.IGNORE_CASE).findAll(sql).lastOrNull()
        ?.let { return it.groups[1]!!.range }
    return null
}

class TerminalColumnAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        val vFile = element.virtualFile ?: return
        if (!vFile.name.endsWith(".sql")) return

        val service = ManifestService.getInstance(element.project)
        val index = service.getIndex()
        if (index === ManifestIndex.EMPTY) return
        val modelId = service.findCurrentModelId(vFile) ?: return
        val node = index.nodes[modelId] ?: return

        val terminal = TerminalColumnAnalyzer.computeTerminalColumns(node, index)
        if (terminal.isEmpty()) return

        val text = element.text
        if (text.isEmpty()) return

        val unlocated = mutableListOf<String>()
        for (col in terminal) {
            val range = locateColumnInProjection(text, col)
            if (range != null) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(TextRange(range.first, range.last + 1))
                    .gutterIconRenderer(
                        TerminalGutterIconRenderer(
                            "Column '$col' is potentially terminal — not consumed by any " +
                                "downstream model or exposure (heuristic)."
                        )
                    )
                    .create()
            } else {
                unlocated += col
            }
        }

        if (unlocated.isNotEmpty()) {
            val firstLineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(0, firstLineEnd.coerceIn(1, text.length)))
                .gutterIconRenderer(
                    TerminalGutterIconRenderer(
                        "Potentially terminal columns (not consumed downstream, heuristic): " +
                            unlocated.sorted().joinToString(", ")
                    )
                )
                .create()
        }
    }
}

private class TerminalGutterIconRenderer(private val tooltip: String) : GutterIconRenderer() {
    override fun getIcon(): Icon = AllIcons.General.Information
    override fun getTooltipText(): String = tooltip
    override fun equals(other: Any?): Boolean =
        other is TerminalGutterIconRenderer && other.tooltip == tooltip
    override fun hashCode(): Int = tooltip.hashCode()
}
