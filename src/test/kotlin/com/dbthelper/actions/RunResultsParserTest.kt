package com.dbthelper.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunResultsParserTest {

    @Test
    fun `parses success and failure statuses with messages and failure counts`() {
        val json = """
            {
              "results": [
                {
                  "unique_id": "model.proj.a",
                  "status": "success",
                  "message": null,
                  "execution_time": 1.23,
                  "failures": 0
                },
                {
                  "unique_id": "test.proj.t1",
                  "status": "fail",
                  "message": "got 3 results, expected 0",
                  "execution_time": 0.45,
                  "failures": 3
                }
              ]
            }
        """.trimIndent()

        val parsed = RunResultsParser().parseString(json)

        assertEquals(2, parsed.size)
        assertEquals(RunStatus.SUCCESS, parsed["model.proj.a"]?.status)
        assertEquals(RunStatus.ERROR, parsed["test.proj.t1"]?.status)
        assertEquals(3, parsed["test.proj.t1"]?.failures)
        assertEquals("got 3 results, expected 0", parsed["test.proj.t1"]?.message)
    }

    @Test
    fun `returns empty map for invalid json`() {
        val parsed = RunResultsParser().parseString("not json at all")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `returns empty map when results array is missing`() {
        val parsed = RunResultsParser().parseString("""{ "metadata": {} }""")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `statuses outside our vocabulary are dropped and tests color no card`() {
        val json = """
            {
              "results": [
                { "unique_id": "model.proj.a", "status": "success" },
                { "unique_id": "model.proj.b", "status": "no-op" },
                { "unique_id": "test.proj.t1", "status": "fail" },
                { "unique_id": "unit_test.proj.u1", "status": "pass" }
              ]
            }
        """.trimIndent()

        val parsed = RunResultsParser().parseString(json)

        assertEquals(setOf("model.proj.a", "test.proj.t1", "unit_test.proj.u1"), parsed.keys)
        assertEquals(mapOf("model.proj.a" to "success"), nodeStatuses(parsed))
    }
}
