package com.dbthelper.codeintel

import com.dbthelper.core.DbtUtils
import com.dbthelper.core.model.*

object DbtDocRenderer {

    private const val MAX_RELATED = 8
    private const val MAX_COLUMNS = 15

    fun buildNodeDoc(node: DbtNode, index: ManifestIndex): String = page {
        append("<h3 style='margin:0 0 6px 0'>${esc(node.name)}</h3>")

        append("<p>")
        append("<code>${node.resourceType}</code>")
        val mat = node.config["materialized"] as? String
        if (mat != null) append(" &middot; <code>$mat</code>")
        append(" &middot; <i>${esc(node.packageName)}</i>")
        append("</p>")

        if (node.database != null || node.schema != null) appendRelation(node.qualifiedName())
        appendDescription(node.description)

        val upstream = index.getUpstream(node.uniqueId)
        val downstream = index.getDownstream(node.uniqueId)
        if (upstream.isNotEmpty() || downstream.isNotEmpty()) {
            append("<hr>")
            appendRelated("Depends on", upstream, index)
            appendRelated("Used by", downstream, index)
        }

        appendColumns(node.columns)
        appendTags(node.tags)
        appendFilePath(node.originalFilePath)
    }

    fun buildSourceDoc(source: DbtSource, index: ManifestIndex): String = page {
        append("<h3 style='margin:0 0 6px 0'>${esc(source.sourceName)}.${esc(source.name)}</h3>")
        append("<p><code>source</code> &middot; <i>${esc(source.packageName)}</i></p>")

        appendRelation(source.qualifiedName())
        appendDescription(source.description)

        val downstream = index.getDownstream(source.uniqueId)
        if (downstream.isNotEmpty()) {
            append("<hr>")
            appendRelated("Used by", downstream, index)
        }

        appendColumns(source.columns)
        appendTags(source.tags)
        appendFilePath(source.originalFilePath)
    }

    fun buildMacroDoc(macro: DbtMacro): String = page {
        val argsStr = macro.arguments.joinToString(", ") { it.name }
        append("<h3 style='margin:0 0 6px 0'>${esc(macro.name)}($argsStr)</h3>")
        append("<p><code>macro</code> &middot; <i>${esc(macro.packageName)}</i></p>")

        appendDescription(macro.description)

        if (macro.arguments.isNotEmpty()) {
            append("<hr><p><b>Arguments:</b></p>")
            append("<table style='margin:2px 0'>")
            for (arg in macro.arguments) {
                append("<tr>")
                append("<td><code>${esc(arg.name)}</code></td>")
                if (arg.type != null) append("<td style='padding-left:8px;color:gray'>${esc(arg.type)}</td>")
                if (arg.description.isNotEmpty()) append("<td style='padding-left:8px'>${esc(arg.description)}</td>")
                append("</tr>")
            }
            append("</table>")
        }

        appendFilePath(macro.originalFilePath)
    }

    private fun page(body: StringBuilder.() -> Unit): String = buildString {
        append("<html><body style='margin:4px'>")
        body()
        append("</body></html>")
    }

    private fun StringBuilder.appendRelation(qualifiedName: String) {
        append("<p><code>${esc(qualifiedName)}</code></p>")
    }

    private fun StringBuilder.appendDescription(description: String) {
        if (description.isNotEmpty()) append("<p style='margin:6px 0'>${esc(description)}</p>")
    }

    private fun StringBuilder.appendRelated(label: String, ids: List<String>, index: ManifestIndex) {
        if (ids.isEmpty()) return
        val names = ids.mapNotNull { DbtUtils.friendlyName(it, index) }.take(MAX_RELATED)
        append("<p><b>$label (${ids.size}):</b> ${names.joinToString(", ") { "<code>${esc(it)}</code>" }}")
        if (ids.size > MAX_RELATED) append(" +${ids.size - MAX_RELATED} more")
        append("</p>")
    }

    private fun StringBuilder.appendColumns(columns: Map<String, DbtColumn>) {
        if (columns.isEmpty()) return
        append("<hr><p><b>Columns (${columns.size}):</b></p>")
        append("<table style='margin:2px 0'>")
        for (col in columns.values.take(MAX_COLUMNS)) {
            append("<tr>")
            append("<td><code>${esc(col.name)}</code></td>")
            append("<td style='padding-left:8px;color:gray'>${esc(col.dataType ?: "")}</td>")
            if (col.description.isNotEmpty()) {
                append("<td style='padding-left:8px'>${esc(col.description)}</td>")
            }
            append("</tr>")
        }
        append("</table>")
        if (columns.size > MAX_COLUMNS) append("<p><i>... and ${columns.size - MAX_COLUMNS} more columns</i></p>")
    }

    private fun StringBuilder.appendTags(tags: List<String>) {
        if (tags.isNotEmpty()) {
            append("<p style='margin-top:6px'><b>Tags:</b> ${tags.joinToString(", ") { "<code>${esc(it)}</code>" }}</p>")
        }
    }

    private fun StringBuilder.appendFilePath(path: String) {
        append("<p style='color:gray;margin-top:6px'>${esc(path)}</p>")
    }

    private fun esc(s: String): String = DbtUtils.escapeHtml(s)
}
