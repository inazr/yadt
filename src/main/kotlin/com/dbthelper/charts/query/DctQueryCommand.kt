package com.dbthelper.charts.query

import java.nio.file.Path

/** `dct query` for one named board query, as JSON so the tab can render rows instead of text. */
object DctQueryCommand {

    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 1000

    fun args(
        dct: Path,
        root: Path,
        boardRelPath: String,
        queryName: String,
        limit: Int,
        vars: Map<String, String>,
    ): List<String> =
        listOf(
            dct.toString(), "query", boardRelPath, queryName,
            "--json", "--limit", limit.coerceIn(MIN_LIMIT, MAX_LIMIT).toString(),
            "--project-dir", root.toString(),
        ) + vars.filterValues { it.isNotBlank() }.flatMap { (name, value) -> listOf("--var", "$name=$value") }
}
