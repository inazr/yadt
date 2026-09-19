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

data class Form(val title: String, val family: String?, val encoding: List<FormField>, val general: List<FormField>, val sections: List<FormField>)

/**
 * Builds the Design form from dct's board schema, the way the dbt Charts platform generates its
 * controls from the YAML language: labels from keys, help from descriptions, choices from enums.
 */
object SchemaForm {

    private val SCALARS = setOf(FieldKind.Text, FieldKind.Number, FieldKind.Bool, FieldKind.Color, FieldKind.Column)
    private val COLOR_NAME = Regex("color|colour|background|fill|ink|stroke")

    fun forBoard(schema: JsonNode): Form = group("Board", null, fields(schema, schema, emptyList(), emptySet()))

    fun forChart(schema: JsonNode, type: String?): Form {
        val defs = schema.path("\$defs")
        val branches = defs.path("AuthoredChart").path("anyOf").mapNotNull { ref(defs, it) }
        val match = branches.firstOrNull { (_, def) -> def.path("properties").path("type").path("enum").any { it.asText() == type } }
        if (match == null) {
            val common = branches.map { (_, def) -> def.path("properties").fieldNames().asSequence().toSet() }
                .reduceOrNull { a, b -> a intersect b }.orEmpty()
            val first = branches.firstOrNull()?.second ?: return group("Chart", null, emptyList())
            return group("Chart", null, fields(schema, first, emptyList(), emptySet()).filter { it.name in common })
        }
        val (defName, def) = match
        return group("${label(type!!)} Chart", family(defName), fields(schema, def, emptyList(), setOf(defName)))
    }

    fun label(key: String): String = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun family(defName: String): String =
        defName.removeSuffix("Chart").replace(Regex("(?<=[a-z])(?=[A-Z])"), "_").lowercase()

    private fun group(title: String, family: String?, all: List<FormField>): Form {
        val visible = all.filter { it.name != "type" }
        return Form(
            title = title,
            family = family,
            encoding = if (family == null) emptyList() else visible.filter { it.kind == FieldKind.Column },
            general = visible.filter { it.kind !is FieldKind.Section && (family == null || it.kind != FieldKind.Column) },
            sections = visible.filter { it.kind is FieldKind.Section },
        )
    }

    private fun fields(schema: JsonNode, def: JsonNode, path: List<String>, seen: Set<String>): List<FormField> =
        def.path("properties").fields().asSequence().map { (name, node) ->
            FormField(name, path + name, label(name), node.path("description").asText(""), kind(schema, name, node, path + name, seen))
        }.toList()

    private fun kind(schema: JsonNode, name: String, node: JsonNode, path: List<String>, seen: Set<String>): FieldKind {
        val defs = schema.path("\$defs")
        val description = node.path("description").asText("").lowercase()
        var n = node
        if (n.has("anyOf")) {
            val alternatives = n.path("anyOf").filter { it.path("type").asText() != "null" }
            n = when {
                alternatives.size == 1 -> alternatives.single()
                alternatives.any { it.path("type").asText() == "string" } -> alternatives.first { it.path("type").asText() == "string" }
                else -> return FieldKind.Unsupported("several shapes")
            }
        }
        var nextSeen = seen
        if (n.has("\$ref")) {
            val (defName, def) = ref(defs, n) ?: return FieldKind.Unsupported("unknown")
            if (defName in seen) return FieldKind.Unsupported("recursive")
            nextSeen = seen + defName
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
                val children = fields(schema, n, path, nextSeen)
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
