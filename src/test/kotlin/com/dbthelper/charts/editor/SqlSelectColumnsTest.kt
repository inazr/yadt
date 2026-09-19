package com.dbthelper.charts.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SqlSelectColumnsTest {

    @Test
    fun `aliases, bare and dotted columns`() = assertEquals(
        listOf("month", "material_flow", "tonnes"),
        SqlSelectColumns.of(
            """
            SELECT
                    DATE(EXTRACT(YEAR FROM t.d), EXTRACT(MONTH FROM t.d), 1) AS month
                ,   t.material_flow
                ,   SUM(t.net_weight_tonnes) tonnes
            FROM {{ ref('t') }}
            WHERE 1=1 AND {% if x %}{{ filter('a', x) }}{% endif %}
            GROUP BY ALL
            """.trimIndent(),
        ),
    )

    @Test
    fun `commas inside functions, strings and Jinja don't split columns`() = assertEquals(
        listOf("label", "n"),
        SqlSelectColumns.of("select concat(a, ', ', b) as label, {{ dbt.count('x, y') }} as n from t"),
    )

    @Test
    fun `only the outer select counts`() = assertEquals(
        listOf("region", "total"),
        SqlSelectColumns.of("select region, total from (select region, sum(x) as total, y from s group by 1)"),
    )

    @Test
    fun `a leading CTE is skipped`() = assertEquals(
        listOf("a"),
        SqlSelectColumns.of("with c as (select 1 as z) select c.z as a from c"),
    )

    @Test
    fun `select star gives no columns`() = assertEquals(emptyList<String>(), SqlSelectColumns.of("select * from t"))
}
