package com.dbthelper.charts.query

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BoardVariablesTest {

    @Test
    fun `reads variables in order with their defaults`() = assertEquals(
        listOf(
            BoardVariables.Variable("date_range", null),
            BoardVariables.Variable("region", "West"),
            BoardVariables.Variable("top_n", "10"),
        ),
        BoardVariables.of(
            """
            title: T
            variables:
              date_range:
                input: daterange
                placeholder: "Year to date"
              region:
                input: select
                default: West
              top_n:
                input: number
                default: 10
            queries:
              q:
                sql: SELECT 1
            """.trimIndent(),
        ),
    )

    @Test
    fun `a board without variables has none`() =
        assertEquals(emptyList<BoardVariables.Variable>(), BoardVariables.of("title: T\nqueries: {}\n"))

    @Test
    fun `broken yaml yields no variables instead of failing`() =
        assertEquals(emptyList<BoardVariables.Variable>(), BoardVariables.of("variables: [oops"))
}
