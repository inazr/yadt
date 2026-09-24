package com.dbthelper.charts.editor

import java.nio.file.Files
import java.nio.file.Path

/**
 * dct's declared field order per schema definition. The JSON schema lists fields alphabetically;
 * `agent_api/docs/yaml-reference.md`, generated from dct's models, keeps the order they are written
 * in (a board: title, notes, tags, …, source, variables, queries, charts, rows, …). Internal path,
 * so a missing or unreadable file just means no order.
 */
class DctFieldOrder(private val orders: Map<String, List<String>>) {

    /** [names] of [defName] in dct's order; fields dct doesn't list follow, in their given order. */
    fun sort(defName: String?, names: List<String>): List<String> {
        val order = defName?.let { orders[it] ?: orders[it.removeSuffix("Patch")] } ?: return names
        val known = names.filter { it in order }.sortedBy(order::indexOf)
        return known + names.filterNot { it in order }
    }

    companion object {
        val EMPTY = DctFieldOrder(emptyMap())

        private val HEADING = Regex("^## (\\S+)")
        private val FIELD_ROW = Regex("^\\|\\s*`([^`]+)`\\s*\\|")

        fun parse(markdown: String): DctFieldOrder {
            val orders = mutableMapOf<String, MutableList<String>>()
            var current: MutableList<String>? = null
            markdown.lineSequence().forEach { line ->
                HEADING.find(line)?.let { current = orders.getOrPut(it.groupValues[1]) { mutableListOf() } }
                    ?: FIELD_ROW.find(line)?.let { current?.add(it.groupValues[1]) }
            }
            return DctFieldOrder(orders)
        }

        fun load(packageDir: Path): DctFieldOrder =
            runCatching { parse(Files.readString(packageDir.resolve("agent_api/docs/yaml-reference.md"))) }.getOrDefault(EMPTY)
    }
}
