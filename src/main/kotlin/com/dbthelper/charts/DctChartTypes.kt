package com.dbthelper.charts

import com.dbthelper.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode

/**
 * The fields each dbt Charts chart type allows and requires, read from dct's board schema
 * (`AuthoredChart` → one definition per chart family, each with `additionalProperties: false`).
 * Used to tell, for the fields a chart already has, which `type:` values fit it.
 */
object DctChartTypes {

    data class Mismatch(val notAllowed: List<String>, val missing: List<String>)

    data class ChartType(val allowed: Set<String>, val required: Set<String>) {
        /** Why this type doesn't fit a chart with [presentKeys], or null if it does. */
        fun mismatch(presentKeys: Set<String>): Mismatch? {
            val notAllowed = (presentKeys - allowed).sorted()
            val missing = (required - presentKeys).sorted()
            return if (notAllowed.isEmpty() && missing.isEmpty()) null else Mismatch(notAllowed, missing)
        }
    }

    /** Chart type name → its fields. Accepts dct's `allOf` of if/then and [DctSchemaPatcher]'s `anyOf`. */
    fun parse(schemaJson: String): Map<String, ChartType> {
        val defs = jsonMapper.readTree(schemaJson).path("\$defs")
        val chart = defs.path("AuthoredChart")
        val branches = chart.path("anyOf").takeIf { it.isArray }?.toList()
            ?: chart.path("allOf").map { it.path("then") }
        return branches
            .mapNotNull { branch -> definition(defs, branch) }
            .flatMap { def ->
                val properties = def.path("properties")
                val type = ChartType(
                    allowed = properties.fieldNames().asSequence().toSet(),
                    required = def.path("required").map { it.asText() }.toSet(),
                )
                properties.path("type").path("enum").map { it.asText() to type }
            }
            .toMap()
    }

    private fun definition(defs: JsonNode, branch: JsonNode): JsonNode? {
        val ref = branch.path("\$ref").asText().takeIf { it.startsWith("#/\$defs/") } ?: return null
        return defs.path(ref.removePrefix("#/\$defs/")).takeIf { it.isObject }
    }
}
