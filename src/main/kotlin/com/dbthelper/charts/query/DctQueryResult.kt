package com.dbthelper.charts.query

import com.dbthelper.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode

/**
 * dct's `query --json` payload. Values are kept as display text: the table shows what dct returned,
 * it never re-types it. Anything that isn't dct JSON (a traceback, an empty stdout) is an error, so
 * the tab always has something to show.
 */
object DctQueryResult {

    data class Table(
        val success: Boolean,
        val columns: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
        val rowCount: Int = 0,
        val truncated: Boolean = false,
        val errors: List<String> = emptyList(),
    )

    val EMPTY = Table(success = true)

    fun parse(json: String): Table = runCatching {
        val root = jsonMapper.readTree(json)
        if (!root.isObject || !root.has("success")) error("not a dct query result")
        val columns = root.path("columns").map { it.asText() }
        Table(
            success = root.path("success").asBoolean(),
            columns = columns,
            rows = root.path("data").map { row -> columns.map { cell(row.path(it)) } },
            rowCount = root.path("row_count").asInt(),
            truncated = root.path("truncated").asBoolean(),
            errors = root.path("errors").map { it.asText() },
        )
    }.getOrElse { Table(success = false, errors = listOf(it.message ?: "could not read dct's output")) }

    private fun cell(value: JsonNode): String = if (value.isNull || value.isMissingNode) "" else value.asText()
}
