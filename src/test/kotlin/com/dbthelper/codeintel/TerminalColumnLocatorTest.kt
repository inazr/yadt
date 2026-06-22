package com.dbthelper.codeintel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TerminalColumnLocatorTest {

    @Test
    fun `locates aliased column`() {
        val sql = "select id, total as revenue from t"
        val range = locateColumnInProjection(sql, "revenue")!!
        assertEquals("revenue", sql.substring(range.first, range.last + 1))
    }

    @Test
    fun `locates bare column`() {
        val sql = "select id, amount from t"
        val range = locateColumnInProjection(sql, "amount")!!
        assertEquals("amount", sql.substring(range.first, range.last + 1))
    }

    @Test
    fun `returns null for column absent from text (select star)`() {
        val sql = "select * from t"
        assertNull(locateColumnInProjection(sql, "amount"))
    }

    @Test
    fun `does not locate a table-qualified reference`() {
        // t.amount is qualified -> treated as not locatable (falls back to file-level marker)
        val sql = "select t.amount from t"
        assertNull(locateColumnInProjection(sql, "amount"))
    }
}
