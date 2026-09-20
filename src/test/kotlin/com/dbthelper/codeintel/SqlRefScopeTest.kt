package com.dbthelper.codeintel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SqlRefScopeTest {

    @Test
    fun `a ref is reachable under its own name`() {
        val scope = SqlRefScope.of("SELECT a FROM {{ ref('fct_orders') }}")
        assertEquals(SqlRefScope.Target.Model("fct_orders"), scope["fct_orders"])
    }

    @Test
    fun `an alias points at the same model, with and without AS`() {
        val scope = SqlRefScope.of(
            """
            SELECT 1
            FROM {{ ref('fct_orders') }} AS o
            JOIN {{ ref('dim_customers') }} c ON c.id = o.customer_id
            """.trimIndent(),
        )
        assertEquals(SqlRefScope.Target.Model("fct_orders"), scope["o"])
        assertEquals(SqlRefScope.Target.Model("dim_customers"), scope["c"])
        assertEquals(SqlRefScope.Target.Model("dim_customers"), scope["dim_customers"])
    }

    @Test
    fun `sources keep their source and table name`() {
        val scope = SqlRefScope.of("SELECT * FROM {{ source('raw', 'events') }} e")
        assertEquals(SqlRefScope.Target.Source("raw", "events"), scope["e"])
        assertEquals(SqlRefScope.Target.Source("raw", "events"), scope["events"])
    }

    @Test
    fun `sql keywords are never taken as an alias`() {
        val scope = SqlRefScope.of("SELECT a FROM {{ ref('fct_orders') }} WHERE a > 1")
        assertNull(scope["WHERE"])
        assertEquals(SqlRefScope.Target.Model("fct_orders"), scope["fct_orders"])
    }

    @Test
    fun `lookup ignores case, like SQL identifiers`() {
        val scope = SqlRefScope.of("FROM {{ ref('Fct_Orders') }} AS O")
        assertEquals(SqlRefScope.Target.Model("Fct_Orders"), scope["o"])
        assertEquals(SqlRefScope.Target.Model("Fct_Orders"), scope["fct_orders"])
    }

    @Test
    fun `text without refs has no targets`() = assertNull(SqlRefScope.of("SELECT 1").get("t"))
}
