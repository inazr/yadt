package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandBuilder
import com.dbthelper.actions.DbtCommandSpec
import com.dbthelper.actions.DbtVerb
import com.dbthelper.core.ProfilesParser
import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.settings.SettingsChangeListener
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Shared settings/action bar shown above the Lineage and Runner tabs.
 * Row 1: selector field | read-only command preview.
 * Row 2: target combo, verb toggle group, full-refresh, Clear, GO.
 *
 * Holds no execution logic — it exposes callbacks the coordinator wires up.
 */
class DbtActionBar(private val project: Project) : JPanel(BorderLayout()) {

    // --- callbacks set by DbtMainPanel ---
    var onGo: ((DbtCommandSpec) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onSelectorChanged: ((String) -> Unit)? = null

    private val selectorField = JBTextField().apply {
        emptyText.text = "dbt selector (e.g. my_model or 1+my_model+2)"
    }
    private val commandPreview = JBTextField().apply {
        isEditable = false
        toolTipText = "The exact dbt command GO will run"
    }

    private val targetCombo = JComboBox<String>().apply {
        toolTipText = "dbt target"
        preferredSize = Dimension(120, preferredSize.height)
    }

    private val verbButtons: Map<DbtVerb, JToggleButton> = DbtVerb.entries.associateWith {
        JToggleButton(it.display)
    }
    private val verbGroup = ButtonGroup().apply { verbButtons.values.forEach { add(it) } }

    private val fullRefreshCheckBox = JCheckBox("full-refresh").apply {
        toolTipText = "Rebuild incremental models from scratch (--full-refresh)"
    }
    private val clearButton = JButton("Clear").apply { toolTipText = "Clear log output" }
    private val goButton = JButton("GO")

    private var running = false
    private var suppressSelectorEvents = false
    private var fullRefreshAllowedForModel = false

    init {
        border = JBUI.Borders.empty(4)

        // Row 1: "dbt Select:" label + selector | preview
        val selectorPanel = JPanel(BorderLayout(4, 0)).apply {
            add(JLabel("dbt Select:"), BorderLayout.WEST)
            add(selectorField, BorderLayout.CENTER)
        }
        val row1 = JPanel(BorderLayout(8, 0)).apply {
            add(selectorPanel, BorderLayout.WEST)
            add(commandPreview, BorderLayout.CENTER)
        }
        selectorField.preferredSize = Dimension(260, selectorField.preferredSize.height)

        // Row 2: target + verbs + flags + actions
        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JLabel("Target:"))
            add(targetCombo)
            verbButtons.values.forEach { add(it) }
            add(fullRefreshCheckBox)
            add(Box.createHorizontalStrut(8))
            add(clearButton)
            add(goButton)
        }

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row1)
            add(row2)
        }
        add(stack, BorderLayout.CENTER)

        initTargetCombo()
        initListeners()
        verbButtons[DbtVerb.RUN]!!.isSelected = true
        updateForVerb()
    }

    // --- public API used by DbtMainPanel ---

    fun setRunning(value: Boolean) {
        running = value
        goButton.text = if (value) "Stop" else "GO"
        selectorField.isEnabled = !value && selectedVerb().usesSelector
        verbButtons.values.forEach { it.isEnabled = !value }
        clearButton.isEnabled = !value
        if (!value) updateGoEnabled()
    }

    /** Auto-fill the selector from the active editor (does not move the graph). */
    fun setSelector(text: String) {
        if (selectorField.text == text) return
        suppressSelectorEvents = true
        try {
            selectorField.text = text
        } finally {
            suppressSelectorEvents = false
        }
        updatePreview()
        updateGoEnabled()
    }

    /** Show/hide full-refresh based on whether the current model is incremental. */
    fun setFullRefreshAvailable(incremental: Boolean) {
        fullRefreshAllowedForModel = incremental
        updateFullRefreshVisibility()
    }

    fun refreshTargets() {
        val settings = DbtHelperSettings.getInstance(project)
        val profiles = ProfilesParser.getInstance(project)
        profiles.invalidateCache()
        val targets = profiles.getTargetNames()
        val current = targetCombo.selectedItem as? String
        targetCombo.actionListeners.forEach { targetCombo.removeActionListener(it) }
        targetCombo.removeAllItems()
        targets.forEach { targetCombo.addItem(it) }
        val restore = (current ?: settings.state.activeTarget).takeIf { targets.contains(it) }
        if (restore != null) targetCombo.selectedItem = restore
        addTargetListener(settings)
    }

    // --- internals ---

    private fun initTargetCombo() {
        val settings = DbtHelperSettings.getInstance(project)
        val profiles = ProfilesParser.getInstance(project)
        val targets = profiles.getTargetNames()
        val defaultTarget = profiles.getDefaultTarget()
        targetCombo.removeAllItems()
        targets.forEach { targetCombo.addItem(it) }
        val current = settings.state.activeTarget.ifBlank { defaultTarget ?: "" }
        if (current.isNotBlank() && targets.contains(current)) targetCombo.selectedItem = current
        addTargetListener(settings)
    }

    private fun addTargetListener(settings: DbtHelperSettings) {
        targetCombo.addActionListener {
            val selected = targetCombo.selectedItem as? String ?: return@addActionListener
            settings.state.activeTarget = selected
            updatePreview()
            project.messageBus.syncPublisher(SettingsChangeListener.TOPIC).onSettingsChanged()
        }
    }

    private fun initListeners() {
        selectorField.document.addDocumentListener(object : DocumentListener {
            private fun changed() {
                updatePreview()
                updateGoEnabled()
                if (!suppressSelectorEvents) onSelectorChanged?.invoke(selectorField.text.trim())
            }
            override fun insertUpdate(e: DocumentEvent?) = changed()
            override fun removeUpdate(e: DocumentEvent?) = changed()
            override fun changedUpdate(e: DocumentEvent?) = changed()
        })
        verbButtons.values.forEach { btn ->
            btn.addActionListener { updateForVerb() }
        }
        fullRefreshCheckBox.addActionListener { updatePreview() }
        clearButton.addActionListener { onClear?.invoke() }
        goButton.addActionListener {
            if (running) onStop?.invoke() else onGo?.invoke(currentSpec())
        }
    }

    private fun selectedVerb(): DbtVerb =
        verbButtons.entries.first { it.value.isSelected }.key

    private fun currentSpec(): DbtCommandSpec {
        val settings = DbtHelperSettings.getInstance(project)
        return DbtCommandSpec(
            verb = selectedVerb(),
            selector = selectorField.text.trim(),
            target = (targetCombo.selectedItem as? String).orEmpty(),
            fullRefresh = fullRefreshCheckBox.isSelected && selectedVerb().supportsFullRefresh,
            previewLimit = settings.state.previewRowLimit
        )
    }

    private fun updateForVerb() {
        val verb = selectedVerb()
        selectorField.isEnabled = !running && verb.usesSelector
        updateFullRefreshVisibility()
        updatePreview()
        updateGoEnabled()
    }

    private fun updateFullRefreshVisibility() {
        val verb = selectedVerb()
        val visible = verb.supportsFullRefresh && fullRefreshAllowedForModel
        fullRefreshCheckBox.isVisible = visible
        if (!visible) fullRefreshCheckBox.isSelected = false
        revalidate()
        repaint()
    }

    private fun updatePreview() {
        commandPreview.text = DbtCommandBuilder.buildDisplay(currentSpec())
    }

    private fun updateGoEnabled() {
        if (running) { goButton.isEnabled = true; return }
        val verb = selectedVerb()
        goButton.isEnabled = !verb.usesSelector || selectorField.text.isNotBlank()
    }
}
