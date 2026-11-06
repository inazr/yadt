package com.dbthelper.charts.editor

import com.fasterxml.jackson.databind.JsonNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Rectangle
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
 * The Design tab: one collapsible section for the board and one per chart, each a form generated
 * from dct's schema. A chart's form is built the first time its section opens. Every edit goes
 * straight into the YAML via [YamlValueWriter]; the sections re-read values on document changes and
 * are rebuilt only when the file's shape changes (other charts, a chart type, theme or column list).
 */
class DesignPanel(
    private val project: Project,
    private val file: VirtualFile,
    parent: Disposable,
    private val onEditInCode: (Int) -> Unit,
) {
    /** An element of the board, independent of the PSI that may momentarily not parse. */
    private sealed interface Id {
        data object Board : Id
        data class Chart(val id: String) : Id
    }

    /** What every field of a built form needs besides itself; [mapping] follows reparses. */
    private class Context(
        val id: Id,
        var mapping: YAMLMapping,
        val columns: List<String>,
        val inherited: (FormField) -> String?,
        val palettes: DctPalettes,
        val theme: String,
        val updaters: MutableList<() -> Unit> = mutableListOf(),
    )

    private data class ElementShape(val id: Id, val chartType: String?, val columns: List<String>)

    /** The shape of the built sections: while it is unchanged, values are re-read into the components. */
    private data class Shape(val elements: List<ElementShape>, val theme: String, val meta: List<Map<String, Any?>>)

    /** One element's section; [context] is set once [build] filled in its form. */
    private class Section(val shape: ElementShape, val row: CollapsibleRow, val holder: JPanel) {
        var context: Context? = null
        var build: () -> Unit = {}

        /** Setting `expanded` from code may not notify the row's listeners, so build explicitly. */
        fun open() {
            row.expanded = true
            build()
        }
    }

    private class Built(
        val shape: Shape,
        val schema: JsonNode,
        val palettes: DctPalettes,
        val order: DctFieldOrder,
        val sections: Map<Id, Section>,
    )

    private var updating = false
    private var built: Built? = null
    private val body = JPanel(BorderLayout())
    val component: JComponent = JBScrollPane(body).apply { border = JBUI.Borders.empty() }

    init {
        project.messageBus.connect(parent).subscribe(
            DesignData.TOPIC,
            DesignData.Listener { update() },
        )
    }

    /** Opens the section of [target] (the caret's element) and scrolls it into view. */
    fun showTarget(target: Target?) {
        update()
        val section = target?.let { built?.sections?.get(it.identity()) } ?: return
        section.open()
        ApplicationManager.getApplication().invokeLater {
            val header = JBUI.scale(SECTION_HEADER)
            section.holder.scrollRectToVisible(Rectangle(0, -header, section.holder.width, header + body.visibleRect.height))
        }
    }

    /** Re-reads the file: the same sections unless its shape changed. */
    fun refresh() = update()

    private fun Target.identity(): Id = if (this is Target.Chart) Id.Chart(id) else Id.Board

    private fun yamlFile(): YAMLFile? = if (file.isValid) PsiManager.getInstance(project).findFile(file) as? YAMLFile else null

    /** Swing callbacks hold no lock in 2026.2+, so every PSI entry point of the form takes one. */
    private fun update() = WriteIntentReadAction.run(Runnable { rebuild() })

    private fun rebuild() {
        val yaml = yamlFile()
        val targets = yaml?.let(BoardTargets::targets).orEmpty()
        val data = DesignData.getInstance(project)
        when (val schema = data.schema()) {
            DesignData.Schema.Loading -> message("Loading dbt Charts schema…")
            DesignData.Schema.Missing -> message("No dbt Charts schema — see Settings → Tools → YADT")
            // A syntax error hides the elements while it is being typed: keep the last good sections.
            is DesignData.Schema.Ready -> if (yaml == null || targets.isEmpty()) stale() else showSections(yaml, targets, schema.node, data)
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

    /** The file doesn't parse right now (usually mid-edit): show the last sections, greyed out. */
    private fun stale() {
        if (built == null) message("Open a dbt Charts board") else UIUtil.setEnabled(body, false, true)
    }

    private fun showSections(yaml: YAMLFile, targets: List<Target>, schema: JsonNode, data: DesignData) {
        val palettes = data.palettes()
        val order = data.fieldOrder()
        val meta = data.metaDocs(file)
        val boardTheme = BoardTargets.root(yaml)?.let { BoardTargets.valueText(it, listOf("theme")) }
        val defaults = ThemeDefaults(palettes, boardTheme, meta)
        val shape = Shape(targets.map { elementShape(yaml, it) }, defaults.theme, meta)
        val previous = built
        if (previous != null && previous.shape == shape && previous.schema === schema && previous.palettes === palettes && previous.order === order) {
            refreshValues(previous, targets)
            return
        }
        val expanded = previous?.sections?.filterValues { it.row.expanded }?.keys.orEmpty()
        val sections = linkedMapOf<Id, Section>()
        val component = panel {
            targets.zip(shape.elements).forEach { (target, element) ->
                if (element.id in sections) return@forEach
                val holder = JPanel(BorderLayout())
                val row = collapsibleGroup(sectionTitle(target, element)) {
                    row { cell(holder).align(AlignX.FILL) }
                }
                val section = Section(element, row, holder)
                sections[element.id] = section
                // A user's click holds no lock, and building the form reads PSI.
                section.build = { WriteIntentReadAction.run(Runnable { buildSection(section, schema, palettes, order, defaults) }) }
                row.addExpandedListener { open -> if (open) section.build() }
                if (element.id in expanded) section.open() else row.expanded = false
            }
        }
        built = Built(shape, schema, palettes, order, sections)
        body.removeAll()
        body.add(component, BorderLayout.NORTH)
        UIUtil.setEnabled(body, true, true)
        body.revalidate()
        body.repaint()
    }

    private fun elementShape(yaml: YAMLFile, target: Target): ElementShape {
        val chart = target as? Target.Chart
        return ElementShape(
            target.identity(),
            chart?.let { BoardTargets.valueText(it.mapping, listOf("type")) },
            chart?.let { queryColumns(yaml, it) }.orEmpty(),
        )
    }

    private fun sectionTitle(target: Target, element: ElementShape): String = when (target) {
        is Target.Chart -> element.chartType?.let { "${target.id} ($it)" } ?: target.id
        else -> "Board"
    }

    /** Same shape: point the built forms at the fresh PSI and re-read every value. */
    private fun refreshValues(previous: Built, targets: List<Target>) {
        updating = true
        try {
            targets.forEach { target ->
                val context = previous.sections[target.identity()]?.context ?: return@forEach
                context.mapping = target.mapping
                context.updaters.forEach { it() }
            }
        } finally {
            updating = false
        }
        UIUtil.setEnabled(body, true, true)
    }

    /** Fills [section] with its form the first time it opens. */
    private fun buildSection(section: Section, schema: JsonNode, palettes: DctPalettes, order: DctFieldOrder, defaults: ThemeDefaults) {
        if (section.context != null) return
        val target = yamlFile()?.let(BoardTargets::targets)?.firstOrNull { it.identity() == section.shape.id } ?: return
        val content = if (target.mapping.text.trimStart().startsWith("{")) {
            // YamlValueWriter edits block mappings as text; an inline `{…}` chart would be corrupted.
            JBLabel("This chart is written inline ({…}) — edit it in Code")
        } else {
            val chart = target is Target.Chart
            val form = if (chart) SchemaForm.forChart(schema, section.shape.chartType, order) else SchemaForm.forBoard(schema, order)
            val context = Context(
                id = section.shape.id,
                mapping = target.mapping,
                columns = section.shape.columns,
                inherited = { f -> if (chart) form.family?.let { defaults.chart(it, f.path) } else defaults.board(f.path) },
                palettes = palettes,
                theme = defaults.theme,
            )
            section.context = context
            panel {
                form.groups.forEach { group ->
                    collapsibleGroup(group.title) {
                        group.fields.forEach { if (it.kind is FieldKind.Section) section(it, context) else field(it, context) }
                    }.expanded = group.expanded
                }
            }
        }
        section.holder.removeAll()
        section.holder.add(content, BorderLayout.CENTER)
        section.holder.revalidate()
        section.holder.repaint()
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
            FieldKind.TextList -> cell(textInput(f, ctx, value, placeholder.ifEmpty { "a, b, …" }, numeric = false))
            is FieldKind.Unsupported -> link("edit in Code") {
                WriteIntentReadAction.run(Runnable {
                    onEditInCode(BoardTargets.keyOffset(ctx.mapping, f.path) ?: ctx.mapping.textRange.startOffset)
                })
            }
            is FieldKind.Compact, is FieldKind.Section -> Unit
        }
    }

    /** A list field reads as its comma-separated items. */
    private fun value(ctx: Context, f: FormField): String? {
        val mapping = ctx.mapping.takeIf { it.isValid } ?: return null
        return if (f.kind == FieldKind.TextList) BoardTargets.valueItems(mapping, f.path)?.joinToString(", ")
        else BoardTargets.valueText(mapping, f.path)
    }

    private fun checkBox(f: FormField, ctx: Context, text: String?) = JBCheckBox(text).apply {
        toolTipText = f.help.ifBlank { null }
        val read = { value(ctx, f)?.toBooleanStrictOrNull() ?: ctx.inherited(f)?.toBooleanStrictOrNull() ?: (f.name == "show" || f.name == "visible") }
        isSelected = read()
        ctx.updaters += { isSelected = read() }
        addActionListener { if (!updating) write(ctx, f, isSelected.toString()) }
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
                if (!invalid && text != committed && write(ctx, f, text)) committed = text
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
                if (selected != committed && write(ctx, f, selected)) committed = selected
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
        addActionListener { ColorPickerPopup.show(this, ctx.palettes, ctx.theme) { token -> write(ctx, f, token) } }
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
    /** `run`, not `compute`: `compute`'s signature changed after 251, a NoSuchMethodError on 262. */
    private fun write(ctx: Context, f: FormField, value: String): Boolean {
        var written = false
        WriteIntentReadAction.run(Runnable { written = writeLocked(ctx, f, value) })
        return written
    }

    private fun writeLocked(ctx: Context, f: FormField, value: String): Boolean {
        val mapping = ctx.mapping.takeIf { it.isValid }
            ?: yamlFile()?.let(BoardTargets::targets)?.firstOrNull { it.identity() == ctx.id }?.mapping?.also { ctx.mapping = it }
            ?: return false
        val items = if (f.kind == FieldKind.TextList) YamlText.items(value) else null
        return when {
            value.isEmpty() || items?.isEmpty() == true -> YamlValueWriter.remove(project, mapping, f.path)
            items != null -> YamlValueWriter.setList(project, mapping, f.path, items)
            else -> YamlValueWriter.set(project, mapping, f.path, value)
        }
    }

    private companion object {
        /** Roughly one section header, so a revealed section shows its title too. */
        const val SECTION_HEADER = 32
    }
}
