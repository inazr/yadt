package com.dbthelper.toolwindow

import com.dbthelper.charts.DbtChartsBoardLocator
import com.dbthelper.charts.query.BoardVariables
import com.dbthelper.charts.query.DctQueryCommand
import com.dbthelper.charts.query.DctQueryResult
import com.dbthelper.charts.query.DctQueryRunner
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * Rows of one dbt Charts board query, run through `dct query` ([DctQueryRunner]). Opened from the
 * gutter icon on a board's `queries:` entry. One text field per board variable, since dct fails a
 * query whose Jinja reads a variable that has no value. EDT only.
 */
class DbtQueryTab(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()), Disposable {

    private val runner get() = DctQueryRunner.getInstance(project)

    private val title = JBLabel("Click the run icon next to a query in a dbt Charts board.")
    private val variablesPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
    private val limit = JBIntSpinner(DEFAULT_LIMIT, DctQueryCommand.MIN_LIMIT, DctQueryCommand.MAX_LIMIT)
    private val runButton = JButton("Run").apply { isEnabled = false }
    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private val status = JBLabel()
    private val errors = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColor.RED
        isOpaque = false
        isVisible = false
    }
    private val model = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    private var board: VirtualFile? = null
    private var queryName: String? = null
    private val variableFields = linkedMapOf<String, JBTextField>()

    init {
        Disposer.register(parentDisposable, this)

        val controls = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JBLabel("Limit:"))
            add(limit)
            add(runButton)
            add(stopButton)
            add(status)
        }
        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
            listOf(title, variablesPanel, controls, errors).forEach {
                it.alignmentX = LEFT_ALIGNMENT
                add(it)
            }
        }
        add(north, BorderLayout.NORTH)
        add(JBScrollPane(JBTable(model)), BorderLayout.CENTER)

        runButton.addActionListener { run() }
        stopButton.addActionListener { stop() }
    }

    /** Shows [queryName] of [board] with fresh variable fields and runs it. */
    fun show(board: VirtualFile, queryName: String) {
        val sameBoard = board == this.board
        val typed = if (sameBoard) variableFields.mapValues { it.value.text } else emptyMap()
        this.board = board
        this.queryName = queryName
        title.text = "${board.name} › $queryName"

        variableFields.clear()
        variablesPanel.removeAll()
        BoardVariables.of(boardText(board)).forEach { variable ->
            val field = JBTextField(typed[variable.name] ?: variable.default.orEmpty(), FIELD_COLUMNS)
            field.addActionListener { run() }
            variableFields[variable.name] = field
            variablesPanel.add(JBLabel("${variable.name}:"))
            variablesPanel.add(field)
        }
        variablesPanel.revalidate()
        variablesPanel.repaint()
        run()
    }

    private fun run() {
        val board = board ?: return
        val queryName = queryName ?: return
        val rootDir = DbtChartsBoardLocator.chartsDirOf(board)?.parent
        val relPath = rootDir?.let { VfsUtilCore.getRelativePath(board, it, '/') }
        if (rootDir == null || relPath == null) {
            render(DctQueryResult.Table(success = false, errors = listOf("${board.name} is no longer a dbt Charts board.")))
            return
        }
        setRunning(true)
        runner.run(
            rootDir.toNioPath(), relPath, queryName, limit.number,
            variableFields.mapValues { it.value.text.trim() },
        ) { table ->
            setRunning(false)
            render(table)
        }
    }

    private fun stop() {
        runner.stop()
        setRunning(false)
        status.text = "Stopped"
    }

    private fun setRunning(running: Boolean) {
        runButton.isEnabled = !running
        stopButton.isEnabled = running
        if (running) status.text = "Running…"
    }

    private fun render(table: DctQueryResult.Table) {
        errors.text = table.errors.joinToString("\n")
        errors.isVisible = table.errors.isNotEmpty()
        status.text = if (!table.success) "" else buildString {
            append(if (table.rowCount == 1) "1 row" else "${table.rowCount} rows")
            if (table.truncated) append(" (truncated)")
        }
        model.setDataVector(
            table.rows.map { it.toTypedArray<Any?>() }.toTypedArray(),
            table.columns.toTypedArray<Any?>(),
        )
        revalidate()
    }

    /** The editor's text if the board is open, so unsaved variable edits count. `getDocument` needs a read lock. */
    private fun boardText(board: VirtualFile): String =
        ApplicationManager.getApplication().runReadAction(Computable { FileDocumentManager.getInstance().getDocument(board)?.text })
            ?: runCatching { String(board.contentsToByteArray(), board.charset) }.getOrDefault("")

    override fun dispose() = runner.stop()

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val FIELD_COLUMNS = 12
    }
}
