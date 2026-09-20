package com.dbthelper.charts.editor

import com.dbthelper.charts.preview.DbtChartsPreviewEditor
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.Alarm
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.KeyboardFocusManager
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.text.JTextComponent
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Board editor like the dbt Charts platform: preview on the left, `Code` (the YAML editor) and
 * `Design` (schema-generated form) tabs on the right. Delegates [TextEditor] to the code editor so
 * navigation, search and carets keep working; navigating switches to `Code`.
 */
class DbtChartsBoardEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val textEditor: TextEditor,
    private val preview: DbtChartsPreviewEditor,
) : TextEditor by textEditor {

    private val design: DesignPanel
    private val tabs = JBTabbedPane()
    private val splitter = JBSplitter(false, SPLITTER_KEY, 0.5f).apply {
        firstComponent = preview.component
        secondComponent = tabs
    }
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val root = JPanel(BorderLayout()).apply {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(TOOLBAR_PLACE, DefaultActionGroup(PreviewToggle()), true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    init {
        // Built here, not in a property initializer, so `this` is fully initialized when it escapes.
        design = DesignPanel(project, file, this) { offset -> showCode(offset) }
        Disposer.register(this, textEditor)
        Disposer.register(this, preview)
        tabs.addTab("Code", textEditor.component)
        tabs.addTab("Design", design.component)
        tabs.addChangeListener { if (tabs.selectedIndex == DESIGN) design.showTarget(targetAtCaret()) }
        // The preview starts hidden and its visibility drives the dct serve lease, so restoring the
        // toggle here is what keeps a board the user hid from starting a server on every reopen.
        preview.component.isVisible = PropertiesComponent.getInstance().getBoolean(PREVIEW_KEY, true)
        textEditor.editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleDesignRefresh()
        }, this)
    }

    private fun scheduleDesignRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refreshDesign() }, REFRESH_DELAY_MS)
    }

    /**
     * Re-reads the form, unless the user is typing into it — a rebuild would drop the edit. The
     * retry keeps the pending refresh alive until they leave the field, so the form never stays
     * stale; everything else (checkboxes, dropdowns, the colour swatch) refreshes right away.
     */
    private fun refreshDesign() {
        if (tabs.selectedIndex != DESIGN) return
        if (isTypingInDesign()) {
            scheduleDesignRefresh()
            return
        }
        WriteIntentReadAction.run(Runnable {
            PsiDocumentManager.getInstance(project).commitDocument(textEditor.editor.document)
            design.refresh()
        })
    }

    /** True while a text field of the form, or of a popup the form opened, holds the focus. */
    private fun isTypingInDesign(): Boolean {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (focus !is JTextComponent) return false
        if (UIUtil.isAncestor(design.component, focus)) return true
        // The colour picker's custom-hex field lives in a popup window owned by the editor's window.
        val panelWindow = UIUtil.getWindow(design.component) ?: return false
        var owner = UIUtil.getWindow(focus)?.owner
        while (owner != null) {
            if (owner === panelWindow) return true
            owner = owner.owner
        }
        return false
    }

    private fun targetAtCaret(): Target? {
        PsiDocumentManager.getInstance(project).commitDocument(textEditor.editor.document)
        val yaml = PsiManager.getInstance(project).findFile(file) as? YAMLFile ?: return null
        return BoardTargets.targetAt(yaml, textEditor.editor.caretModel.offset)
    }

    private fun showCode(offset: Int) {
        tabs.selectedIndex = CODE
        textEditor.editor.caretModel.moveToOffset(offset)
        textEditor.editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        textEditor.preferredFocusedComponent?.requestFocusInWindow()
    }

    override fun getComponent(): JComponent = root
    override fun getName(): String = "dbt Charts"

    /**
     * Kotlin's interface delegation does not cover `FileEditor.getFile`'s Java default body, so
     * without this the platform hits the deprecated default and logs a PluginException.
     */
    override fun getFile(): VirtualFile = file

    /** The code editor is not showing while `Design` is selected, so it can't take the focus back. */
    override fun getPreferredFocusedComponent(): JComponent? =
        if (tabs.selectedIndex == DESIGN) UIUtil.getPreferredFocusedComponent(design.component) ?: design.component
        else textEditor.preferredFocusedComponent

    override fun navigateTo(navigatable: Navigatable) {
        tabs.selectedIndex = CODE
        textEditor.navigateTo(navigatable)
    }

    override fun dispose() = Unit // textEditor and preview are disposed as children of this editor

    private inner class PreviewToggle : ToggleAction("Show Preview", null, AllIcons.Actions.PreviewDetails) {
        override fun isSelected(e: AnActionEvent): Boolean = preview.component.isVisible

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            preview.component.isVisible = state
            PropertiesComponent.getInstance().setValue(PREVIEW_KEY, state, true)
            splitter.revalidate()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private companion object {
        const val TOOLBAR_PLACE = "DbtChartsBoardEditor"
        const val SPLITTER_KEY = "yadt.dbtCharts.boardEditor"
        const val PREVIEW_KEY = "$SPLITTER_KEY.preview"
        const val CODE = 0
        const val DESIGN = 1
        const val REFRESH_DELAY_MS = 300
    }
}
