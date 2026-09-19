package com.dbthelper.charts

import com.dbthelper.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Rewrites dct's board schema so the IDE's JSON Schema completion handles it. dct describes a chart
 * as `allOf: [{if: {type: bar}, then: BarChart}, …]`. For completion, IntelliJ ignores the `if`s and
 * ANDs every `then` together, so the last branch's `type` enum wins and only `spark_bar` is offered.
 * An `anyOf` of the `then` branches is equivalent for valid boards (each branch pins its own `type`)
 * and completes the union.
 */
object DctSchemaPatcher {

    fun patch(schemaJson: String): String {
        val root = jsonMapper.readTree(schemaJson)
        rewrite(root)
        return jsonMapper.writeValueAsString(root)
    }

    private fun rewrite(node: JsonNode) {
        if (node is ObjectNode) {
            val allOf = node.get("allOf")
            if (allOf != null && allOf.isArray && !node.has("anyOf") && allOf.size() > 0 && allOf.all(::isIfThen)) {
                node.remove("allOf")
                node.putArray("anyOf").addAll(allOf.map { it.get("then") })
            }
        }
        node.forEach(::rewrite)
    }

    private fun isIfThen(branch: JsonNode): Boolean =
        branch.isObject && branch.size() == 2 && branch.has("if") && branch.has("then")
}
