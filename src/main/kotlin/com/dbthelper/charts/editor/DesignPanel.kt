package com.dbthelper.charts.editor

import com.fasterxml.jackson.databind.JsonNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.JPanel
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The Design tab: a form for the board or one chart, generated from dct's schema. Every edit goes
 * straight into the YAML via [YamlValueWriter]; the form re-reads values on document changes.
 */
class DesignPanel(
    private val project: Project,
    private val file: VirtualFile,
    @Suppress("UNUSED_PARAMETER") parent: Disposable,
    private val onEditInCode: (Int) -> Unit,
) {
    /** What every field of the current form needs besides itself. */
    private class Context(
        val target: Target,
        val columns: List<String>,
        val inherited: (FormField) -> String?,
        val palettes: DctPalettes,
        val theme: String,
    )

    private var updating = false
    private var current: Target? = null
    private val selector = ComboBox<Target>().apply {
        renderer = SimpleListCellRenderer.create("") { t -> if (t is Target.Chart) t.id else "Board" }
        addActionListener { if (!updating) (selectedItem as? Target)?.let { current = it; rebuild() } }
    }
    private val body = JPanel(BorderLayout())
    val component: JComponent = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6); add(selector, BorderLayout.CENTER) }, BorderLayout.NORTH)
        add(JBScrollPane(body), BorderLayout.CENTER)
    }

    fun showTarget(target: Target?) {
        val targets = yamlFile()?.let(BoardTargets::targets).orEmpty()
        updating = true
        try {
            selector.removeAllItems()
            targets.forEach(selector::addItem)
            current = targets.firstOrNull { it.sameAs(target) } ?: targets.firstOrNull()
            selector.selectedItem = current
        } finally {
            updating = false
        }
        rebuild()
    }

    /** Re-reads the file and rebuilds the form for the same target (v1: always a full rebuild). */
    fun refresh() = showTarget(current)

    private fun Target.sameAs(other: Target?) = when {
        this is Target.Board && other is Target.Board -> true
        this is Target.Chart && other is Target.Chart -> id == other.id
        else -> false
    }

    private fun yamlFile(): YAMLFile? = if (file.isValid) PsiManager.getInstance(project).findFile(file) as? YAMLFile else null

    private fun rebuild() {
        val target = current
        val yaml = yamlFile()
        val schema = DesignData.getInstance(project).schema()
        body.removeAll()
        when {
            schema == null -> body.add(message("No dbt Charts schema — see Settings → Tools → YADT"), BorderLayout.NORTH)
            target == null || yaml == null -> body.add(message("Open a dbt Charts board"), BorderLayout.NORTH)
            // YamlValueWriter edits block mappings as text; an inline `{…}` chart would be corrupted.
            target.mapping.text.trimStart().startsWith("{") ->
                body.add(message("This chart is written inline ({…}) — edit it in Code"), BorderLayout.NORTH)
            else -> body.add(form(yaml, target, schema), BorderLayout.NORTH)
        }
        body.revalidate()
        body.repaint()
    }

    private fun message(text: String) = JBLabel(text).apply { border = JBUI.Borders.empty(12) }

    private fun form(yaml: YAMLFile, target: Target, schema: JsonNode): JComponent {
        val data = DesignData.getInstance(project)
        val palettes = data.palettes()
        val boardTheme = BoardTargets.root(yaml)?.let { BoardTargets.valueText(it, listOf("theme")) }
        val defaults = ThemeDefaults(palettes, boardTheme, data.metaDocs(file))
        val chart = target as? Target.Chart
        val form = if (chart != null) SchemaForm.forChart(schema, BoardTargets.valueText(chart.mapping, listOf("type"))) else SchemaForm.forBoard(schema)
        val inherited: (FormField) -> String? = { f ->
            if (chart != null) form.family?.let { defaults.chart(it, f.path) } else defaults.board(f.path)
        }
        val ctx = Context(target, chart?.let { queryColumns(yaml, it) }.orEmpty(), inherited, palettes, defaults.theme)
        return panel {
            row { label(form.title).bold() }
            if (form.encoding.isNotEmpty()) group("Encoding") { form.encoding.forEach { field(it, ctx) } }
            group("General") { form.general.forEach { field(it, ctx) } }
            form.sections.forEach { section(it, ctx) }
        }
    }

    private fun Panel.section(f: FormField, ctx: Context) {
        val kind = f.kind as FieldKind.Section
        collapsibleGroup(f.label) {
            kind.toggle?.let { toggle ->
                row {
                    icon(AllIcons.Actions.Show)
                    cell(JBCheckBox("Visible").apply {
                        isSelected = value(ctx, toggle)?.toBooleanStrictOrNull() ?: inherited(ctx, toggle)?.toBooleanStrictOrNull() ?: true
                        addActionListener { write(ctx.target, toggle.path, isSelected.toString()) }
                    })
                }
            }
            kind.children.forEach { child -> if (child.kind is FieldKind.Section) section(child, ctx) else field(child, ctx) }
        }
    }

    private fun Panel.field(f: FormField, ctx: Context) {
        row(f.label) {
            when (val kind = f.kind) {
                is FieldKind.Compact -> kind.children.forEach { input(it, ctx, compact = true) }
                is FieldKind.Section -> Unit
                else -> input(f, ctx, compact = false)
            }
            if (f.help.isNotBlank()) contextHelp(f.help)
        }
    }

    /** The input(s) for one scalar field; in a compact row the label becomes the placeholder. */
    private fun Row.input(f: FormField, ctx: Context, compact: Boolean) {
        val value = value(ctx, f)
        val inherited = inherited(ctx, f)
        val placeholder = inherited?.let { "$it · inherited" } ?: if (compact) f.label else ""
        when (val kind = f.kind) {
            FieldKind.Bool -> cell(JBCheckBox(if (compact) f.label else null).apply {
                isSelected = value?.toBooleanStrictOrNull() ?: inherited?.toBooleanStrictOrNull() ?: false
                toolTipText = f.help.ifBlank { null }
                addActionListener { write(ctx.target, f.path, isSelected.toString()) }
            })
            is FieldKind.Choice -> cell(choice(ctx.target, f, kind.values, value, placeholder, editable = false))
            FieldKind.Column ->
                if (ctx.columns.isEmpty()) cell(textInput(ctx.target, f, value, placeholder, numeric = false))
                else cell(choice(ctx.target, f, ctx.columns, value, placeholder, editable = true))
            FieldKind.Color -> {
                val hex = (value ?: inherited)?.let { ctx.palettes.resolve(it, ctx.theme) ?: it }
                cell(ColorPickerPopup.swatch(value ?: inherited.orEmpty(), hex.orEmpty()) {}.apply {
                    addActionListener { ColorPickerPopup.show(this, ctx.palettes, ctx.theme) { token -> write(ctx.target, f.path, token) } }
                })
                cell(textInput(ctx.target, f, value, placeholder, numeric = false))
            }
            FieldKind.Number -> cell(textInput(ctx.target, f, value, placeholder, numeric = true))
            FieldKind.Text -> cell(textInput(ctx.target, f, value, placeholder, numeric = false))
            is FieldKind.Unsupported -> link("edit in Code") {
                onEditInCode(BoardTargets.keyOffset(ctx.target.mapping, f.path) ?: ctx.target.mapping.textRange.startOffset)
            }
            is FieldKind.Compact, is FieldKind.Section -> Unit
        }
    }

    private fun value(ctx: Context, f: FormField): String? = BoardTargets.valueText(ctx.target.mapping, f.path)

    private fun inherited(ctx: Context, f: FormField): String? = ctx.inherited(f)

    private fun textInput(target: Target, f: FormField, value: String?, placeholder: String, numeric: Boolean): JBTextField =
        JBTextField(value.orEmpty(), 12).apply {
            emptyText.text = placeholder
            toolTipText = f.help.ifBlank { null }
            val normal = foreground
            var committed = value.orEmpty()
            val commit = {
                val text = text.trim()
                val invalid = numeric && text.isNotEmpty() && text.toDoubleOrNull() == null
                foreground = if (invalid) JBColor.RED else normal
                if (!invalid && text != committed) {
                    committed = text
                    write(target, f.path, text)
                }
            }
            addActionListener { commit() }
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = commit()
            })
        }

    /** A dropdown whose first, empty entry means "not set" (shown as the inherited placeholder). */
    private fun choice(target: Target, f: FormField, values: List<String>, value: String?, placeholder: String, editable: Boolean): ComboBox<String> =
        ComboBox((listOf("") + values + listOfNotNull(value)).distinct().toTypedArray()).apply {
            isEditable = editable
            selectedItem = value.orEmpty()
            renderer = SimpleListCellRenderer.create("") { v -> v.ifEmpty { placeholder } }
            toolTipText = f.help.ifBlank { null }
            var committed = value.orEmpty()
            addActionListener {
                val selected = (selectedItem as? String)?.trim() ?: return@addActionListener
                if (selected != committed) {
                    committed = selected
                    write(target, f.path, selected)
                }
            }
        }

    private fun queryColumns(yaml: YAMLFile, chart: Target.Chart): List<String> {
        val ref = BoardTargets.valueText(chart.mapping, listOf("query"))?.removePrefix("queries.") ?: return emptyList()
        val root = BoardTargets.root(yaml) ?: return emptyList()
        val sql = BoardTargets.valueText(root, listOf("queries", ref, "sql")) ?: return emptyList()
        return SqlSelectColumns.of(sql)
    }

    /**
     * Writes to the live mapping of [target]: an earlier edit may have reparsed the file while the
     * form (and its captured PSI) stayed on screen because a field kept the focus.
     */
    private fun write(target: Target, path: List<String>, value: String) {
        val mapping = target.mapping.takeIf { it.isValid }
            ?: yamlFile()?.let(BoardTargets::targets)?.firstOrNull { it.sameAs(target) }?.mapping
            ?: return
        if (value.isEmpty()) YamlValueWriter.remove(project, mapping, path)
        else YamlValueWriter.set(project, mapping, path, value)
    }
}
