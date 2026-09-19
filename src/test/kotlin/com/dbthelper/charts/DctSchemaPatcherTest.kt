package com.dbthelper.charts

import com.dbthelper.core.jsonMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DctSchemaPatcherTest {

    private fun patched(json: String) = jsonMapper.readTree(DctSchemaPatcher.patch(json))

    private val chart = """
        {"${'$'}defs": {"AuthoredChart": {
          "properties": {"type": {"enum": ["bar", "line"]}},
          "required": ["type"],
          "allOf": [
            {"if": {"properties": {"type": {"enum": ["bar"]}}}, "then": {"${'$'}ref": "#/${'$'}defs/BarChart"}},
            {"if": {"properties": {"type": {"enum": ["line"]}}}, "then": {"${'$'}ref": "#/${'$'}defs/LineChart"}}
          ]
        }}}
    """.trimIndent()

    @Test
    fun `an allOf of if-then branches becomes an anyOf of the then branches`() {
        val node = patched(chart).path("${'$'}defs").path("AuthoredChart")
        assertFalse(node.has("allOf"))
        assertEquals(
            listOf("#/${'$'}defs/BarChart", "#/${'$'}defs/LineChart"),
            node.path("anyOf").map { it.path("${'$'}ref").asText() },
        )
    }

    @Test
    fun `the rest of the schema object is kept`() {
        val node = patched(chart).path("${'$'}defs").path("AuthoredChart")
        assertEquals(listOf("bar", "line"), node.path("properties").path("type").path("enum").map { it.asText() })
        assertEquals("type", node.path("required")[0].asText())
    }

    @Test
    fun `an allOf that isn't purely if-then is left alone`() {
        val json = """{"allOf": [{"if": {}, "then": {}}, {"${'$'}ref": "#/x"}]}"""
        assertEquals(jsonMapper.readTree(json), patched(json))
    }

    @Test
    fun `if-then with an else is left alone`() {
        val json = """{"allOf": [{"if": {}, "then": {}, "else": {}}]}"""
        assertEquals(jsonMapper.readTree(json), patched(json))
    }
}
