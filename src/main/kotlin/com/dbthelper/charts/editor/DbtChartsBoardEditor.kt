package com.dbthelper.charts.editor

import com.dbthelper.charts.preview.DbtChartsPreviewEditor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
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

    private val tabs = JBTabbedPane()
    private val design = DesignPanel(project, file, this) { offset -> showCode(offset) }
    private val splitter = JBSplitter(false, "yadt.dbtCharts.boardEditor", 0.5f).apply {
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
        Disposer.register(this, textEditor)
        Disposer.register(this, preview)
        tabs.addTab("Code", textEditor.component)
        tabs.addTab("Design", design.component)
        tabs.addChangeListener { if (tabs.selectedIndex == DESIGN) design.showTarget(targetAtCaret()) }
        // The preview starts hidden (its visibility drives the dct serve pause); show it by default.
        preview.component.isVisible = true
        textEditor.editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                refreshAlarm.cancelAllRequests()
                refreshAlarm.addRequest({ refreshDesign() }, REFRESH_DELAY_MS)
            }
        }, this)
    }

    /** Rebuilds the form unless it holds the focus: a rebuild while typing would drop the edit. */
    private fun refreshDesign() {
        if (tabs.selectedIndex != DESIGN) return
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (focus != null && UIUtil.isAncestor(design.component, focus)) return
        PsiDocumentManager.getInstance(project).commitDocument(textEditor.editor.document)
        design.refresh()
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

    override fun navigateTo(navigatable: Navigatable) {
        tabs.selectedIndex = CODE
        textEditor.navigateTo(navigatable)
    }

    override fun dispose() = Unit // textEditor and preview are disposed as children of this editor

    private inner class PreviewToggle : ToggleAction("Show Preview", null, AllIcons.Actions.PreviewDetails) {
        override fun isSelected(e: AnActionEvent): Boolean = preview.component.isVisible

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            preview.component.isVisible = state
            splitter.revalidate()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private companion object {
        const val TOOLBAR_PLACE = "DbtChartsBoardEditor"
        const val CODE = 0
        const val DESIGN = 1
        const val REFRESH_DELAY_MS = 300
    }
}
