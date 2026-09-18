package com.dbthelper.charts.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * Shows a board as rendered by `dct serve`. The page reloads itself through dct's livereload
 * whenever the file on disk changes, so this editor only saves the document after a typing pause
 * and loads the board URL; startup/failure messages replace the browser with a status panel.
 *
 * Works only while the preview is visible: in the "Editor only" layout it releases its server lease,
 * blanks the page (dropping livereload) and stops saving, so hidden boards never re-run warehouse
 * queries. Visibility is the signal because TextEditorWithPreview applies every layout — toolbar,
 * restored state or the remembered default — through the preview component's `isVisible`, while
 * `onLayoutChange` only fires for the toolbar.
 */
class DbtChartsPreviewEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val root: Path,
    private val boardUrl: String,
) : UserDataHolderBase(), FileEditor {

    private val browser = JBCefBrowser()
    private val message = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(12)
    }
    private val retry = JButton("Retry").apply { addActionListener { connect() } }
    private val cards = CardLayout()
    private val component = JPanel(cards).apply {
        val status = JPanel(BorderLayout()).apply {
            add(JBScrollPane(message), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(retry) }, BorderLayout.SOUTH)
        }
        add(status, STATUS)
        add(browser.component, BROWSER)
        // Hidden until the host's layout shows it, so "Editor only" never fires componentShown.
        isVisible = false
        addComponentListener(object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) = resume()
            override fun componentHidden(e: ComponentEvent) = pause()
        })
    }
    private val saveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var lease: Disposable? = null
    private var active = false

    /** Set when the shown page is an error page without dct's livereload (see [DctPageStatus]). */
    @Volatile
    private var needsManualReload = false

    init {
        Disposer.register(this, browser)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) needsManualReload = DctPageStatus.needsManualReload(httpStatusCode)
            }
        }, browser.cefBrowser)
        FileDocumentManager.getInstance().getDocument(file)?.let { document ->
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (!active) return
                    saveAlarm.cancelAllRequests()
                    saveAlarm.addRequest({
                        WriteIntentReadAction.run(Runnable { FileDocumentManager.getInstance().saveDocument(document) })
                        if (needsManualReload) browser.cefBrowser.reload()
                    }, SAVE_DELAY_MS)
                }
            }, this)
        }
    }

    private fun resume() {
        if (active) return
        active = true
        connect()
    }

    private fun pause() {
        if (!active) return
        active = false
        saveAlarm.cancelAllRequests()
        lease?.let(Disposer::dispose)
        lease = null
        browser.loadURL("about:blank")
        showStatus("Preview paused.", retryable = false)
    }

    private fun connect() {
        lease?.let(Disposer::dispose)
        val current = Disposer.newDisposable(this, "dct serve lease")
        lease = current
        showStatus("Starting dct serve…", retryable = false)
        DctServeService.getInstance(project).acquire(root, current) { result ->
            ApplicationManager.getApplication().invokeLater({ show(result) }, ModalityState.nonModal()) { lease !== current }
        }
    }

    private fun show(result: DctServeResult) = when (result) {
        is DctServeResult.Ready -> {
            browser.loadURL("http://127.0.0.1:${result.port}$boardUrl")
            cards.show(component, BROWSER)
        }
        is DctServeResult.Failed -> showStatus(result.message, retryable = true)
    }

    private fun showStatus(text: String, retryable: Boolean) {
        message.text = text
        retry.isVisible = retryable
        cards.show(component, STATUS)
    }

    override fun getComponent(): JComponent = component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName(): String = "dbt Charts Preview"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        lease = null
    }

    private companion object {
        const val STATUS = "status"
        const val BROWSER = "browser"
        const val SAVE_DELAY_MS = 1000
    }
}
