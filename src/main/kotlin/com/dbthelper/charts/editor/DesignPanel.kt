package com.dbthelper.charts.editor

import com.fasterxml.jackson.databind.JsonNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteIntentReadAction
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
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Renders [text] of the selected value into a combo box cell. Both [SimpleListCellRenderer.create]
 * overloads are `@Deprecated(forRemoval = true)` from the 2026.2 platform on, so this subclasses
 * [SimpleListCellRenderer] directly instead — the class and its abstract `customize` are not
 * deprecated, and always calling [text] with a possibly-null value keeps the same "no selection"
 * behavior the removed `create(nullValue, Function)` overload gave for free.
 */
private fun <T> textRenderer(text: (T?) -> String): SimpleListCellRenderer<T> = object : SimpleListCellRenderer<T>() {
    override fun customize(list: JList<out T>, value: T, index: Int, selected: Boolean, hasFocus: Boolean) {
        setText(text(value))
    }
}

/**
 * The Design tab: a form for the board or one chart, generated from dct's schema. Every edit goes
 * straight into the YAML via [YamlValueWriter]; the form re-reads values on document changes and is
 * rebuilt only when its shape changes (a different target, chart type, theme or column list).
 */
class DesignPanel(
    private val project: Project,
    private val file: VirtualFile,
    parent: Disposable,
    private val onEditInCode: (Int) -> Unit,
) {
    /** A target the user asked for, independent of the PSI that may momentarily not parse. */
    private sealed interface Id {
        data object Board : Id
        data class Chart(val id: String) : Id
    }

    /** What every field of the current form needs besides itself; [mapping] follows reparses. */
    private class Context(
        val id: Id,
        var mapping: YAMLMapping,
        val columns: List<String>,
        val inherited: (FormField) -> String?,
        val palettes: DctPalettes,
        val theme: String,
        val updaters: MutableList<() -> Unit> = mutableListOf(),
    )

    /** The shape of a built form: while it is unchanged, values are re-read into the components. */
    private data class Shape(
        val id: Id,
        val chartType: String?,
        val theme: String,
        val columns: List<String>,
        val meta: List<Map<String, Any?>>,
    )

    private class Built(val shape: Shape, val schema: JsonNode, val palettes: DctPalettes, val context: Context)

    private var updating = false
    private var requested: Id? = null
    private var built: Built? = null
    private val selector = ComboBox<Target>().apply {
        renderer = textRenderer<Target> { t ->
            when (t) {
                null -> ""
                is Target.Chart -> t.id
                else -> "Board"
            }
        }
        addActionListener {
            if (!updating) (selectedItem as? Target)?.let { requested = it.identity(); update() }
        }
    }
    private val body = JPanel(BorderLayout())
    val component: JComponent = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6); add(selector, BorderLayout.CENTER) }, BorderLayout.NORTH)
        add(JBScrollPane(body), BorderLayout.CENTER)
    }

    init {
        project.messageBus.connect(parent).subscribe(
            DesignData.TOPIC,
            DesignData.Listener { update() },
        )
    }

    /** Shows [target] (the caret's target); a null target keeps the one already selected. */
    fun showTarget(target: Target?) {
        if (target != null) requested = target.identity()
        update()
    }

    /** Re-reads the file: same target, and the same form unless its shape changed. */
    fun refresh() = update()

    private fun Target.identity(): Id = if (this is Target.Chart) Id.Chart(id) else Id.Board

    private fun yamlFile(): YAMLFile? = if (file.isValid) PsiManager.getInstance(project).findFile(file) as? YAMLFile else null

    /** Swing callbacks hold no lock in 2026.2+, so every PSI entry point of the form takes one. */
    private fun update() = WriteIntentReadAction.run(Runnable { rebuild() })

    private fun rebuild() {
        val yaml = yamlFile()
        val targets = yaml?.let(BoardTargets::targets).orEmpty()
        if (requested == null) requested = targets.firstOrNull()?.identity()
        syncSelector(targets)
        val data = DesignData.getInstance(project)
        val target = targets.firstOrNull { it.identity() == requested }
        when (val schema = data.schema()) {
            DesignData.Schema.Loading -> message("Loading dbt Charts schema…")
            DesignData.Schema.Missing -> message("No dbt Charts schema — see Settings → Tools → YADT")
            is DesignData.Schema.Ready -> when {
                // A syntax error hides the target while it is being typed: keep the last good form.
                target == null || yaml == null -> stale()
                // YamlValueWriter edits block mappings as text; an inline `{…}` chart would be corrupted.
                target.mapping.text.trimStart().startsWith("{") -> message("This chart is written inline ({…}) — edit it in Code")
                else -> showForm(yaml, target, schema.node, data)
            }
        }
    }

    /** Keeps the selector on the requested target, and its items on the last list that had it. */
    private fun syncSelector(targets: List<Target>) {
        if (targets.none { it.identity() == requested } && selector.itemCount > 0) return
        val shown = (0 until selector.itemCount).map { selector.getItemAt(it).identity() }
        updating = true
        try {
            if (shown != targets.map { it.identity() }) {
                selector.removeAllItems()
                targets.forEach(selector::addItem)
            }
            val selected = targets.firstOrNull { it.identity() == requested }
            if (selected != null && (selector.selectedItem as? Target)?.identity() != selected.identity()) {
                selector.selectedItem = selected
            }
        } finally {
            updating = false
        }
    }

    private fun message(text: String) {
        built = null
        body.removeAll()
        body.add(JBLabel(text).apply { border = JBUI.Borders.empty(12) }, BorderLayout.NORTH)
        UIUtil.setEnabled(body, true, true)
        body.revalidate()
        body.repaint()
    }

    /** The target isn't in the file right now (usually mid-edit): show the last form, greyed out. */
    private fun stale() {
        if (built == null) message("Open a dbt Charts board") else UIUtil.setEnabled(body, false, true)
    }

    private fun showForm(yaml: YAMLFile, target: Target, schema: JsonNode, data: DesignData) {
        val palettes = data.palettes()
        val meta = data.metaDocs(file)
        val boardTheme = BoardTargets.root(yaml)?.let { BoardTargets.valueText(it, listOf("theme")) }
        val defaults = ThemeDefaults(palettes, boardTheme, meta)
        val chart = target as? Target.Chart
        val chartType = chart?.let { BoardTargets.valueText(it.mapping, listOf("type")) }
        val columns = chart?.let { queryColumns(yaml, it) }.orEmpty()
        val shape = Shape(target.identity(), chartType, defaults.theme, columns, meta)
        val previous = built
        if (previous != null && previous.shape == shape && previous.schema === schema && previous.palettes === palettes) {
            previous.context.mapping = target.mapping
            updating = true
            try {
                previous.context.updaters.forEach { it() }
            } finally {
                updating = false
            }
            UIUtil.setEnabled(body, true, true)
            return
        }
        val form = if (chart != null) SchemaForm.forChart(schema, chartType) else SchemaForm.forBoard(schema)
        val context = Context(
            id = target.identity(),
            mapping = target.mapping,
            columns = columns,
            inherited = { f -> if (chart != null) form.family?.let { defaults.chart(it, f.path) } else defaults.board(f.path) },
            palettes = palettes,
            theme = defaults.theme,
        )
        val component = panel {
            row { label(form.title).bold() }
            if (form.encoding.isNotEmpty()) group("Encoding") { form.encoding.forEach { field(it, context) } }
            group("General") { form.general.forEach { field(it, context) } }
            form.sections.forEach { section(it, context) }
        }
        built = Built(shape, schema, palettes, context)
        body.removeAll()
        body.add(component, BorderLayout.NORTH)
        UIUtil.setEnabled(body, true, true)
        body.revalidate()
        body.repaint()
    }

    private fun Panel.section(f: FormField, ctx: Context) {
        val kind = f.kind as FieldKind.Section
        collapsibleGroup(f.label) {
            kind.toggle?.let { toggle ->
                row {
                    icon(AllIcons.Actions.Show)
                    cell(checkBox(toggle, ctx, "Visible"))
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
        val placeholder = ctx.inherited(f)?.let { "$it · inherited" } ?: if (compact) f.label else ""
        when (val kind = f.kind) {
            FieldKind.Bool -> cell(checkBox(f, ctx, if (compact) f.label else null))
            is FieldKind.Choice -> cell(choice(f, ctx, kind.values, value, placeholder, editable = false))
            FieldKind.Column ->
                if (ctx.columns.isEmpty()) cell(textInput(f, ctx, value, placeholder, numeric = false))
                else cell(choice(f, ctx, ctx.columns, value, placeholder, editable = true))
            FieldKind.Color -> {
                cell(swatch(f, ctx))
                cell(textInput(f, ctx, value, placeholder, numeric = false))
            }
            FieldKind.Number -> cell(textInput(f, ctx, value, placeholder, numeric = true))
            FieldKind.Text -> cell(textInput(f, ctx, value, placeholder, numeric = false))
            is FieldKind.Unsupported -> link("edit in Code") {
                WriteIntentReadAction.run(Runnable {
                    onEditInCode(BoardTargets.keyOffset(ctx.mapping, f.path) ?: ctx.mapping.textRange.startOffset)
                })
            }
            is FieldKind.Compact, is FieldKind.Section -> Unit
        }
    }

    private fun value(ctx: Context, f: FormField): String? =
        ctx.mapping.takeIf { it.isValid }?.let { BoardTargets.valueText(it, f.path) }

    private fun checkBox(f: FormField, ctx: Context, text: String?) = JBCheckBox(text).apply {
        toolTipText = f.help.ifBlank { null }
        val read = { value(ctx, f)?.toBooleanStrictOrNull() ?: ctx.inherited(f)?.toBooleanStrictOrNull() ?: (f.name == "show" || f.name == "visible") }
        isSelected = read()
        ctx.updaters += { isSelected = read() }
        addActionListener { if (!updating) write(ctx, f.path, isSelected.toString()) }
    }

    private fun textInput(f: FormField, ctx: Context, value: String?, placeholder: String, numeric: Boolean): JBTextField =
        JBTextField(value.orEmpty(), 12).apply {
            emptyText.text = placeholder
            toolTipText = f.help.ifBlank { null }
            val normal = foreground
            var committed = value.orEmpty()
            val commit = {
                val text = text.trim()
                val invalid = numeric && text.isNotEmpty() && text.toDoubleOrNull() == null
                foreground = if (invalid) JBColor.RED else normal
                if (!invalid && text != committed && write(ctx, f.path, text)) committed = text
            }
            ctx.updaters += {
                val fresh = value(ctx, f).orEmpty()
                committed = fresh
                foreground = normal
                if (!hasFocus() && text != fresh) text = fresh
            }
            addActionListener { commit() }
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = commit()
            })
        }

    /** A dropdown whose first, empty entry means "not set" (shown as the inherited placeholder). */
    private fun choice(f: FormField, ctx: Context, values: List<String>, value: String?, placeholder: String, editable: Boolean): ComboBox<String> =
        ComboBox((listOf("") + values + listOfNotNull(value)).distinct().toTypedArray()).apply {
            isEditable = editable
            selectedItem = value.orEmpty()
            renderer = textRenderer<String> { v -> v?.ifEmpty { placeholder } ?: "" }
            toolTipText = f.help.ifBlank { null }
            var committed = value.orEmpty()
            ctx.updaters += {
                val fresh = value(ctx, f).orEmpty()
                committed = fresh
                if ((0 until itemCount).none { getItemAt(it) == fresh }) addItem(fresh)
                if (selectedItem != fresh) selectedItem = fresh
            }
            addActionListener {
                if (updating) return@addActionListener
                val selected = (selectedItem as? String)?.trim() ?: return@addActionListener
                if (selected != committed && write(ctx, f.path, selected)) committed = selected
            }
        }

    /** The colour swatch: shows the resolved colour, opens the token picker. */
    private fun swatch(f: FormField, ctx: Context) = ColorPickerPopup.swatch("", "").apply {
        val read = {
            val token = value(ctx, f) ?: ctx.inherited(f)
            ColorPickerPopup.paint(this, token.orEmpty(), token?.let { ctx.palettes.resolve(it, ctx.theme) ?: it }.orEmpty())
        }
        read()
        ctx.updaters += read
        addActionListener { ColorPickerPopup.show(this, ctx.palettes, ctx.theme) { token -> write(ctx, f.path, token) } }
    }

    private fun queryColumns(yaml: YAMLFile, chart: Target.Chart): List<String> {
        val ref = BoardTargets.valueText(chart.mapping, listOf("query"))?.removePrefix("queries.") ?: return emptyList()
        val root = BoardTargets.root(yaml) ?: return emptyList()
        val sql = BoardTargets.valueText(root, listOf("queries", ref, "sql")) ?: return emptyList()
        return SqlSelectColumns.of(sql)
    }

    /**
     * Writes to the live mapping of the form's target: an earlier edit may have reparsed the file
     * while the form (and its captured PSI) stayed on screen because a field kept the focus.
     * Returns false when the target is gone, or [YamlValueWriter] refused the edit (a flow mapping
     * on the path), so the field keeps the value as uncommitted.
     */
    private fun write(ctx: Context, path: List<String>, value: String): Boolean =
        WriteIntentReadAction.compute<Boolean, RuntimeException> { writeLocked(ctx, path, value) }

    private fun writeLocked(ctx: Context, path: List<String>, value: String): Boolean {
        val mapping = ctx.mapping.takeIf { it.isValid }
            ?: yamlFile()?.let(BoardTargets::targets)?.firstOrNull { it.identity() == ctx.id }?.mapping?.also { ctx.mapping = it }
            ?: return false
        return if (value.isEmpty()) YamlValueWriter.remove(project, mapping, path)
        else YamlValueWriter.set(project, mapping, path, value)
    }
}
