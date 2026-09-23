package com.dbthelper.charts.query

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DctQueryCommandTest {

    private fun args(limit: Int = 20, vars: Map<String, String> = emptyMap()) =
        DctQueryCommand.args(Path.of("/bin/dct"), Path.of("/work/proj"), "charts/board.yml", "monthly_volume", limit, vars)

    @Test
    fun `runs the named query of a board as json`() = assertEquals(
        listOf(
            "/bin/dct", "query", "charts/board.yml", "monthly_volume",
            "--json", "--limit", "20", "--project-dir", "/work/proj",
        ),
        args(),
    )

    @Test
    fun `variables are passed one --var each, blanks left out`() = assertEquals(
        listOf("--var", "date_range=2026-01-01..2026-03-31", "--var", "region=West"),
        args(vars = linkedMapOf("date_range" to "2026-01-01..2026-03-31", "empty" to "  ", "region" to "West")).takeLast(4),
    )

    @Test
    fun `limit is clamped to dct's own range`() {
        assertEquals("1", args(limit = 0)[6])
        assertEquals("1000", args(limit = 5000)[6])
    }
}
