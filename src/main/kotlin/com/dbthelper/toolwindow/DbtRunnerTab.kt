package com.dbthelper.toolwindow

import com.dbthelper.settings.DbtHelperSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.text.AttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Log surface for dbt command output. All controls live in DbtActionBar;
 * DbtMainPanel drives this tab via appendLine/clear and owns run-state.
 *
 * Output is rendered into a JTextPane that tracks the viewport width, so lines
 * soft-wrap and no horizontal scrollbar appears. When "Colored dbt output" is
 * enabled, ANSI SGR sequences are rendered as text styles; otherwise leaked
 * codes are stripped.
 */
class DbtRunnerTab(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private val logPane = object : JTextPane() {
        // Track the viewport width so output soft-wraps (no horizontal scrollbar).
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, 12).takeIf { it.family == "JetBrains Mono" }
            ?: Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    private val logDoc get() = logPane.styledDocument

    // Matches an ANSI SGR sequence: ESC [ <params> m
    private val ansiRegex = Regex("\u001B\\[([0-9;]*)m")

    private val baseStyle: AttributeSet = SimpleAttributeSet()

    // Carried across lines so multi-line colored output keeps its style (EDT-only).
    private var currentStyle: AttributeSet = baseStyle

    init {
        Disposer.register(parentDisposable, this)
        add(JBScrollPane(logPane).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
    }

    /** Append a line to the log (safe to call from any thread). */
    fun appendLine(line: String) {
        ApplicationManager.getApplication().invokeLater { appendLogLine(line) }
    }

    /** Clear the log (safe to call from any thread). */
    fun clear() {
        ApplicationManager.getApplication().invokeLater {
            logDoc.remove(0, logDoc.length)
            currentStyle = baseStyle
        }
    }

    /** Append a line (possibly multi-line) to the styled document. Must run on the EDT. */
    private fun appendLogLine(line: String) {
        val coloredEnabled = DbtHelperSettings.getInstance(project).state.enableColoredOutput
        if (!coloredEnabled) {
            // Strip ANSI sequences so leaked codes don't show as garbage.
            logDoc.insertString(logDoc.length, ansiRegex.replace(line, "") + "\n", baseStyle)
        } else {
            var pos = 0
            for (m in ansiRegex.findAll(line)) {
                if (m.range.first > pos) {
                    logDoc.insertString(logDoc.length, line.substring(pos, m.range.first), currentStyle)
                }
                currentStyle = styleForAnsiParams(m.groupValues[1])
                pos = m.range.last + 1
            }
            if (pos < line.length) {
                logDoc.insertString(logDoc.length, line.substring(pos), currentStyle)
            }
            logDoc.insertString(logDoc.length, "\n", currentStyle)
        }
        logPane.caretPosition = logDoc.length
    }

    /** Apply each SGR code in order on top of the current style. */
    private fun styleForAnsiParams(params: String): AttributeSet {
        val codes = if (params.isBlank()) listOf(0) else params.split(';').mapNotNull { it.toIntOrNull() }
        var attrs = SimpleAttributeSet(currentStyle)
        for (code in codes) {
            when (code) {
                0 -> attrs = SimpleAttributeSet(baseStyle)
                1 -> StyleConstants.setBold(attrs, true)
                22 -> StyleConstants.setBold(attrs, false)
                in 30..37 -> StyleConstants.setForeground(attrs, ansiColor(code - 30, bright = false))
                in 90..97 -> StyleConstants.setForeground(attrs, ansiColor(code - 90, bright = true))
                39 -> attrs.removeAttribute(StyleConstants.Foreground)
            }
        }
        return attrs
    }

    private fun ansiColor(idx: Int, bright: Boolean): Color = when (idx) {
        0 -> if (bright) Color(102, 102, 102) else Color(0, 0, 0)         // black / gray
        1 -> if (bright) Color(255, 85, 85) else Color(205, 49, 49)        // red
        2 -> if (bright) Color(80, 200, 120) else Color(13, 188, 121)      // green
        3 -> if (bright) Color(245, 245, 67) else Color(229, 229, 16)      // yellow
        4 -> if (bright) Color(95, 159, 255) else Color(36, 114, 200)      // blue
        5 -> if (bright) Color(214, 112, 214) else Color(188, 63, 188)     // magenta
        6 -> if (bright) Color(85, 255, 255) else Color(17, 168, 205)      // cyan
        7 -> if (bright) Color(229, 229, 229) else Color(170, 170, 170)    // white
        else -> Color(229, 229, 229)
    }

    /**
     * Format `dbt show --output json` output as an ASCII table, or null if the
     * output doesn't contain a parseable JSON result.
     */
    fun formatPreviewTable(output: String): String? {
        try {
            val clean = output.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")
            val jsonStart = clean.indexOfFirst { it == '{' || it == '[' }
            if (jsonStart < 0) return null
            val jsonStr = clean.substring(jsonStart)
            val rootNode = mapper.readTree(jsonStr)

            val rowsNode: JsonNode = when {
                rootNode.isObject && rootNode.has("show") -> rootNode.get("show")
                rootNode.isArray -> rootNode
                else -> return null
            }
            if (!rowsNode.isArray || rowsNode.size() == 0) return "(0 rows)"

            val firstRow = rowsNode[0]
            val columns = firstRow.fieldNames().asSequence().toList()
            val widths = columns.map { col ->
                val dataMax = (0 until rowsNode.size()).maxOf { i ->
                    nodeToString(rowsNode[i].get(col)).length
                }
                maxOf(col.length, dataMax).coerceAtMost(60)
            }

            val sb = StringBuilder()
            val separator = widths.joinToString("-+-", "+-", "-+") { "-".repeat(it) }
            sb.appendLine(separator)
            sb.appendLine(columns.mapIndexed { i, col -> col.padEnd(widths[i]) }.joinToString(" | ", "| ", " |"))
            sb.appendLine(separator)
            for (i in 0 until rowsNode.size()) {
                val row = rowsNode[i]
                val line = columns.mapIndexed { j, col ->
                    val value = nodeToString(row.get(col))
                    if (value.length > widths[j]) value.take(widths[j] - 3) + "..." else value.padEnd(widths[j])
                }.joinToString(" | ", "| ", " |")
                sb.appendLine(line)
            }
            sb.appendLine(separator)
            sb.appendLine("(${rowsNode.size()} rows)")
            return sb.toString()
        } catch (_: Exception) {
            return null
        }
    }

    private fun nodeToString(node: JsonNode?): String {
        if (node == null || node.isNull) return "null"
        if (node.isTextual) return node.asText()
        if (node.isNumber) return node.numberValue().toString()
        if (node.isBoolean) return node.asBoolean().toString()
        return node.toString()
    }

    override fun dispose() {}
}
