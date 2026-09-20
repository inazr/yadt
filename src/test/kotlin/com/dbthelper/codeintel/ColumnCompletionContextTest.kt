package com.dbthelper.codeintel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ColumnCompletionContextTest {

    private fun context(text: String) = DbtJinjaUtils.detectCompletionContext(text)

    @Test
    fun `a qualifier and a dot start a column completion`() =
        assertEquals(DbtJinjaUtils.CompletionContext.Column("fct_orders", ""), context("SELECT fct_orders."))

    @Test
    fun `typing after the dot narrows the prefix`() =
        assertEquals(DbtJinjaUtils.CompletionContext.Column("o", "cust"), context("SELECT o.cust"))

    @Test
    fun `inside a Jinja call the ref completion still wins`() =
        assertEquals(DbtJinjaUtils.CompletionContext.Ref("fct_"), context("FROM {{ ref('fct_"))

    @Test
    fun `no dot means no column completion`() = assertNull(context("SELECT amount"))

    @Test
    fun `a number with a decimal point is not a qualifier`() = assertNull(context("SELECT 1.2"))

    @Test
    fun `an unfinished Jinja expression is not a column qualifier`() =
        assertEquals(DbtJinjaUtils.CompletionContext.Macro("dbt"), context("SELECT {{ dbt"))
}
