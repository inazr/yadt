package com.dbthelper.charts.editor

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColorPicker
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** The platform's colour picker: Theme tokens, all palettes, or a custom hex. */
object ColorPickerPopup {

    private val HEX = Regex("#[0-9a-fA-F]{6}")
    private const val PALETTES_HEIGHT = 360

    fun show(owner: JComponent, palettes: DctPalettes, theme: String, onPick: (String) -> Unit) {
        lateinit var close: () -> Unit
        val pick: (String) -> Unit = { token -> onPick(token); close() }
        val tabs = JBTabbedPane()
        if (palettes.palettes.isNotEmpty()) {
            tabs.addTab("Theme", themeTab(palettes, theme, pick))
            tabs.addTab("Palettes", palettesTab(palettes, pick))
        }
        tabs.addTab("Custom", customTab(owner, pick))
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(tabs, tabs)
            .setRequestFocus(true).setCancelOnClickOutside(true).createPopup()
        close = { popup.cancel() }
        popup.showUnderneathOf(owner)
    }

    private fun themeTab(palettes: DctPalettes, theme: String, pick: (String) -> Unit): JComponent {
        val roles = palettes.paletteRoles(theme)
        fun size(name: String?) = palettes.palettes[name]?.colors?.size ?: 0
        val rows = listOf(
            "Ink" to listOf("ink"),
            "Tones" to listOf("positive.solid", "negative.solid", "warning.solid", "info.solid"),
            "Category · ${roles["category"].orEmpty()}" to indexed("category", size(roles["category"])),
            "Chrome · dbt-grays" to indexed("dbt-grays", size("dbt-grays"), dotted = true),
            "Diverge · dbt-div-blue-red" to indexed("dbt-div-blue-red", size("dbt-div-blue-red"), dotted = true),
            "Sequence · dbt-seq-blue" to indexed("dbt-seq-blue", size("dbt-seq-blue"), dotted = true),
        )
        return column(rows.map { (title, tokens) -> title to tokens.mapNotNull { t -> palettes.resolve(t, theme)?.let { t to it } } }, pick)
    }

    /** Scrolls: there are dozens of palettes, and the tallest tab sets the popup's height. */
    private fun palettesTab(palettes: DctPalettes, pick: (String) -> Unit): JComponent {
        val list = column(
            palettes.palettes.values.sortedWith(compareBy({ it.family }, { it.name })).filter { it.colors.isNotEmpty() }
                .map { p -> "${p.family} · ${p.name}" to p.colors.mapIndexed { i, hex -> "${p.name}.${i + 1}" to hex } },
            pick,
        )
        return JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            val width = list.preferredSize.width + verticalScrollBar.preferredSize.width
            preferredSize = Dimension(width, minOf(list.preferredSize.height, JBUI.scale(PALETTES_HEIGHT)))
        }
    }

    private fun customTab(owner: JComponent, pick: (String) -> Unit): JComponent {
        val hex = JBTextField(8)
        val apply = JButton("Apply").apply {
            addActionListener {
                val text = hex.text.trim()
                if (text.matches(HEX)) pick(text) else hex.foreground = JBColor.RED
            }
        }
        val choose = JButton("Pick…").apply {
            addActionListener {
                ColorPicker.showDialog(owner, "Colour", null, false, emptyList(), false)?.let {
                    pick("#%02X%02X%02X".format(it.red, it.green, it.blue))
                }
            }
        }
        return JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JBLabel("Hex")); add(hex); add(apply); add(choose) }
    }

    private fun indexed(prefix: String, count: Int, dotted: Boolean = false): List<String> =
        (1..count).map { if (dotted) "$prefix.$it" else "$prefix[$it]" }

    private fun column(rows: List<Pair<String, List<Pair<String, String>>>>, pick: (String) -> Unit): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            // BoxLayout hands spare height to rows that may grow and centres mismatched alignments:
            // pin each row to its own height and everything to the left edge.
            rows.filter { it.second.isNotEmpty() }.forEach { (title, swatches) ->
                add(JBLabel(title).apply {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    alignmentX = Component.LEFT_ALIGNMENT
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 2)).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    swatches.forEach { (token, hex) -> add(swatch(token, hex, pick)) }
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                })
            }
            add(Box.createVerticalGlue())
        }

    /** A square button painted in [hex] (grey if it isn't a `#rrggbb` colour), without a listener. */
    fun swatch(token: String, hex: String): JButton = JButton().apply {
        preferredSize = Dimension(JBUI.scale(18), JBUI.scale(18))
        isContentAreaFilled = false
        isOpaque = true
        paint(this, token, hex)
    }

    /** Repaints a [swatch] for a new value, so a form can reuse the button it already built. */
    fun paint(swatch: JButton, token: String, hex: String) {
        swatch.background = parse(hex)
        swatch.toolTipText = "$token  $hex".trim().ifEmpty { null }
    }

    private fun swatch(token: String, hex: String, pick: (String) -> Unit): JButton =
        swatch(token, hex).apply { addActionListener { pick(token) } }

    private fun parse(hex: String): Color =
        hex.trim().take(7).takeIf { it.matches(HEX) }?.let { runCatching { Color.decode(it) }.getOrNull() } ?: JBColor.GRAY
}
