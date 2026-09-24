package com.dbthelper.charts.editor

import com.fasterxml.jackson.databind.JsonNode

sealed interface FieldKind {
    data object Text : FieldKind
    data object Number : FieldKind
    data object Bool : FieldKind
    data object Color : FieldKind
    data object Column : FieldKind
    data class Choice(val values: List<String>) : FieldKind
    data class Compact(val children: List<FormField>) : FieldKind
    data class Section(val children: List<FormField>, val toggle: FormField?) : FieldKind
    data class Unsupported(val reason: String) : FieldKind
}

data class FormField(val name: String, val path: List<String>, val label: String, val help: String, val kind: FieldKind)

/** A titled block of the form; [expanded] is how it starts. */
data class FieldGroup(val title: String, val expanded: Boolean, val fields: List<FormField>)

data class Form(val title: String, val family: String?, val groups: List<FieldGroup>)

/**
 * Builds the Design form from dct's board schema, the way the dbt Charts platform generates its
 * controls from the YAML language: labels from keys, help from descriptions, choices from enums.
 */
object SchemaForm {

    private val SCALARS = setOf(FieldKind.Text, FieldKind.Number, FieldKind.Bool, FieldKind.Color, FieldKind.Column)
    // Anchored to a name segment (start or after `_`) so "link"/"header_link" don't match on "ink".
    private val COLOR_NAME = Regex("(^|_)(color|colour|background|fill|ink|stroke)")
    private val NUMERIC_TYPES = setOf("integer", "number")

    /**
     * The groups a form's top-level fields fall into, after dct's own grouping of board fields
     * (identity → data → layout → presentation). A field no group names lands in Advanced, so a
     * field a newer dct adds still shows up.
     */
    private class GroupSpec(val title: String, val expanded: Boolean, val names: Set<String>, val columns: Boolean = false)

    private val BOARD_GROUPS = listOf(
        GroupSpec("Content", true, setOf("title", "text", "notes", "tags")),
        GroupSpec("Data", true, setOf("source", "variables", "queries", "cache", "incremental")),
        GroupSpec("Layout", false, setOf("rows", "cols", "grid", "tabs", "charts", "card_gap", "chart_focus", "details")),
        GroupSpec("Appearance", false, setOf("theme", "style", "width", "height")),
    )
    private val CHART_GROUPS = listOf(
        GroupSpec("Content", true, setOf("title", "subtitle", "label", "message")),
        GroupSpec("Data", true, setOf("query")),
        GroupSpec("Encoding", true, setOf("x_label", "y_label", "sort", "multiples"), columns = true),
        GroupSpec("Size", false, setOf("height", "width")),
        GroupSpec(STYLE, false, setOf("style")),
    )
    private const val STYLE = "Style"
    private const val ADVANCED = "Advanced"

    fun forBoard(schema: JsonNode, order: DctFieldOrder = DctFieldOrder.EMPTY): Form =
        form("Board", null, BOARD_GROUPS, fields(schema, schema, BOARD_DEF, emptyList(), emptySet(), order))

    fun forChart(schema: JsonNode, type: String?, order: DctFieldOrder = DctFieldOrder.EMPTY): Form {
        val defs = schema.path("\$defs")
        val branches = defs.path("AuthoredChart").path("anyOf").mapNotNull { ref(defs, it) }
        val match = branches.firstOrNull { (_, def) -> def.path("properties").path("type").path("enum").any { it.asText() == type } }
        if (match == null) {
            val common = branches.map { (_, def) -> def.path("properties").fieldNames().asSequence().toSet() }
                .reduceOrNull { a, b -> a intersect b }.orEmpty()
            val (firstName, first) = branches.firstOrNull() ?: return form("Chart", null, CHART_GROUPS, emptyList())
            return form("Chart", null, CHART_GROUPS, fields(schema, first, firstName, emptyList(), emptySet(), order).filter { it.name in common })
        }
        val (defName, def) = match
        return form("${label(type!!)} Chart", family(defName), CHART_GROUPS, fields(schema, def, defName, emptyList(), setOf(defName), order))
    }

    /** The root board's heading in dct's reference (the schema itself calls it AuthoredBoard). */
    private const val BOARD_DEF = "Board"

    fun label(key: String): String = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun family(defName: String): String =
        defName.removeSuffix("Chart").replace(Regex("(?<=[a-z])(?=[A-Z])"), "_").lowercase()

    private fun form(title: String, family: String?, specs: List<GroupSpec>, all: List<FormField>): Form {
        val visible = all.filter { it.name != "type" }
        fun specOf(f: FormField) = specs.firstOrNull { f.name in it.names || (it.columns && f.kind == FieldKind.Column) }
        val groups = specs.map { spec -> FieldGroup(spec.title, spec.expanded, visible.filter { specOf(it) === spec }) } +
            FieldGroup(ADVANCED, false, visible.filter { specOf(it) == null })
        return Form(title, family, groups.map(::flattenStyle).filter { it.fields.isNotEmpty() })
    }

    /** The Style group shows the `style` section's own fields rather than one "Style" section inside it. */
    private fun flattenStyle(group: FieldGroup): FieldGroup {
        val section = group.fields.singleOrNull()?.kind as? FieldKind.Section
        if (group.title != STYLE || section == null) return group
        return group.copy(fields = listOfNotNull(section.toggle) + section.children)
    }

    private fun fields(schema: JsonNode, def: JsonNode, defName: String?, path: List<String>, seen: Set<String>, order: DctFieldOrder): List<FormField> {
        val properties = def.path("properties")
        return order.sort(defName, properties.fieldNames().asSequence().toList()).map { name ->
            val node = properties.path(name)
            FormField(name, path + name, label(name), node.path("description").asText(""), kind(schema, name, node, path + name, seen, order))
        }
    }

    private fun kind(schema: JsonNode, name: String, node: JsonNode, path: List<String>, seen: Set<String>, order: DctFieldOrder): FieldKind {
        val defs = schema.path("\$defs")
        val description = node.path("description").asText("").lowercase()
        var n = node
        if (n.has("anyOf")) {
            val alternatives = n.path("anyOf").filter { it.path("type").asText() != "null" }
            n = when {
                alternatives.size == 1 -> alternatives.single()
                alternatives.any { it.path("type").asText() == "string" } -> alternatives.first { it.path("type").asText() == "string" }
                // `anyOf: [integer, number, null]` (dct's width/height fields): either numeric shape
                // renders the same Number field, so take the first rather than falling to Unsupported.
                alternatives.isNotEmpty() && alternatives.all { it.path("type").asText() in NUMERIC_TYPES } -> alternatives.first()
                else -> return FieldKind.Unsupported("several shapes")
            }
        }
        var nextSeen = seen
        var defName: String? = null
        if (n.has("\$ref")) {
            val (refName, def) = ref(defs, n) ?: return FieldKind.Unsupported("unknown")
            if (refName in seen) return FieldKind.Unsupported("recursive")
            nextSeen = seen + refName
            defName = refName
            n = def
        }
        val type = n.path("type").asText()
        return when {
            n.has("enum") -> FieldKind.Choice(n.path("enum").map { it.asText() })
            type == "boolean" -> FieldKind.Bool
            type == "integer" || type == "number" -> FieldKind.Number
            type == "array" -> FieldKind.Unsupported("list")
            type == "string" && (description.startsWith("column") || "column name" in description) -> FieldKind.Column
            type == "string" && (COLOR_NAME.containsMatchIn(name) || "color" in description) -> FieldKind.Color
            type == "string" -> FieldKind.Text
            n.has("properties") -> {
                val children = fields(schema, n, defName, path, nextSeen, order)
                if (children.size <= 4 && children.all { it.kind in SCALARS || it.kind is FieldKind.Choice }) FieldKind.Compact(children)
                else {
                    val toggle = children.firstOrNull { it.kind == FieldKind.Bool && (it.name == "show" || it.name == "visible") }
                    FieldKind.Section(children - listOfNotNull(toggle).toSet(), toggle)
                }
            }
            else -> FieldKind.Unsupported("unknown")
        }
    }

    private fun ref(defs: JsonNode, node: JsonNode): Pair<String, JsonNode>? {
        val name = node.path("\$ref").asText().removePrefix("#/\$defs/").takeIf { it.isNotEmpty() } ?: return null
        return defs.path(name).takeIf { it.isObject }?.let { name to it }
    }
}
