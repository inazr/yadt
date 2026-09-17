package com.dbthelper.toolwindow.selector

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * As-you-type suggestion popup for the dbt selector field. Attaches its own listeners to
 * [field]; the field's existing DocumentListener / Enter ActionListener (in DbtActionBar)
 * stay intact. The popup is non-focusable so the field keeps focus while the user types.
 *
 * Enter/Tab accept and are consumed here only while the popup is visible. DbtActionBar's Enter
 * handler additionally guards on [isShowing] so the authoritative `dbt ls` trigger never fires
 * while a suggestion is being chosen — making correct behaviour independent of whether the
 * KeyListener or the field's key binding runs first.
 */
class SelectorAutocompletePopup(
    private val field: JBTextField,
    private val provider: SelectorSuggestionProvider
) {
    private val model = DefaultListModel<SelectorSuggestion>()
    private val list = JBList(model).apply {
        visibleRowCount = MAX_VISIBLE_ROWS
        cellRenderer = textListCellRenderer { it?.text }
    }
    private var popup: JBPopup? = null
    private var accepting = false

    init {
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refresh()
            override fun removeUpdate(e: DocumentEvent?) = refresh()
            override fun changedUpdate(e: DocumentEvent?) = refresh()
        })
        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!isShowing()) return
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> { moveSelection(1); e.consume() }
                    KeyEvent.VK_UP -> { moveSelection(-1); e.consume() }
                    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> { accept(); e.consume() }
                    KeyEvent.VK_ESCAPE -> { hide(); e.consume() }
                }
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = accept()
        })
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) = hide()
        })
    }

    /** True while the suggestion popup is on screen. */
    fun isShowing(): Boolean = popup?.isVisible == true

    private fun refresh() {
        if (accepting) return // accept() drives the document; its own trailing refresh() is authoritative
        if (!field.isEnabled || !field.isFocusOwner) { hide(); return }
        val ctx = SelectorTokenContext.parse(field.text, field.caretPosition)
        if (ctx.query.length < MIN_QUERY_CHARS) { hide(); return }
        val suggestions = provider.suggest(ctx)
        if (suggestions.isEmpty()) { hide(); return }
        model.clear()
        suggestions.forEach { model.addElement(it) }
        list.selectedIndex = 0
        show()
    }

    private fun show() {
        if (isShowing()) return // model mutated in place; the live JList repaints itself
        val p = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(list), field)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(true)
            .createPopup()
        popup = p
        p.showUnderneathOf(field)
    }

    private fun hide() {
        popup?.cancel()
        popup = null
    }

    private fun moveSelection(delta: Int) {
        val size = model.size
        if (size == 0) return
        val next = ((list.selectedIndex + delta) % size + size) % size
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    private fun accept() {
        val chosen = list.selectedValue ?: return
        val ctx = SelectorTokenContext.parse(field.text, field.caretPosition)
        val text = field.text
        val newText = text.substring(0, ctx.replaceStart) + chosen.text + text.substring(ctx.replaceEnd)
        val newCaret = (ctx.replaceStart + chosen.text.length).coerceAtMost(newText.length)
        // The setText below fires the DocumentListener synchronously; suppress that reentrant
        // refresh so it can't parse at the not-yet-updated caret.
        accepting = true
        try {
            hide()
            field.text = newText
            field.caretPosition = newCaret
        } finally {
            accepting = false
        }
        // Single authoritative refresh with the correct caret: reopens for `tag:`-style values.
        refresh()
    }

    companion object {
        private const val MIN_QUERY_CHARS = 2
        private const val MAX_VISIBLE_ROWS = 10
    }
}
