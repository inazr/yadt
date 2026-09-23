package com.dbthelper.charts.query

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DctQueryResultTest {

    @Test
    fun `reads columns and rows in column order`() {
        val table = DctQueryResult.parse(
            """
            {"success": true, "name": "q", "columns": ["a", "b"],
             "data": [{"a": "x", "b": 1}, {"b": 3.5, "a": null}],
             "row_count": 2, "truncated": true, "errors": []}
            """.trimIndent(),
        )
        assertTrue(table.success)
        assertEquals(listOf("a", "b"), table.columns)
        assertEquals(listOf(listOf("x", "1"), listOf("", "3.5")), table.rows)
        assertEquals(2, table.rowCount)
        assertTrue(table.truncated)
    }

    @Test
    fun `a failed run carries dct's messages and no rows`() {
        val table = DctQueryResult.parse(
            """
            {"success": false, "columns": [], "data": [], "row_count": 0, "truncated": false,
             "errors": ["Jinja template error: Undefined variable: 'date_range' is undefined"]}
            """.trimIndent(),
        )
        assertFalse(table.success)
        assertEquals(listOf("Jinja template error: Undefined variable: 'date_range' is undefined"), table.errors)
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun `output that isn't dct json becomes an error`() {
        val table = DctQueryResult.parse("Traceback (most recent call last):")
        assertFalse(table.success)
        assertEquals(1, table.errors.size)
    }
}
