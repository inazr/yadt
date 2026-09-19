package com.dbthelper.charts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DctChartTypesTest {

    private val d = "${'$'}"

    /** dct's shape: AuthoredChart -> allOf of if/then (or, after DctSchemaPatcher, anyOf of the thens). */
    private fun schema(branches: String) = """
        {"${d}defs": {
          "AuthoredChart": {"properties": {"type": {"enum": ["bar", "kpi"]}}, "required": ["type"], $branches},
          "BarChart": {"properties": {"type": {"enum": ["bar", "histogram"]}, "x": {}, "y": {}, "color": {}},
                       "additionalProperties": false, "required": ["type"]},
          "KpiChart": {"properties": {"type": {"enum": ["kpi"]}, "value": {}, "title": {}},
                       "additionalProperties": false, "required": ["type", "value"]}
        }}
    """.trimIndent()

    private val patched = schema(""""anyOf": [{"${'$'}ref": "#/${d}defs/BarChart"}, {"${'$'}ref": "#/${d}defs/KpiChart"}]""")
    private val original = schema(
        """"allOf": [{"if": {}, "then": {"${'$'}ref": "#/${d}defs/BarChart"}}, {"if": {}, "then": {"${'$'}ref": "#/${d}defs/KpiChart"}}]""",
    )

    @Test
    fun `reads every chart type with its fields, from either schema shape`() {
        for (json in listOf(patched, original)) {
            val types = DctChartTypes.parse(json)
            assertEquals(setOf("bar", "histogram", "kpi"), types.keys)
            assertEquals(setOf("type", "x", "y", "color"), types.getValue("histogram").allowed)
            assertEquals(setOf("type", "value"), types.getValue("kpi").required)
        }
    }

    @Test
    fun `a type that fits the chart has no mismatch`() =
        assertNull(DctChartTypes.parse(patched).getValue("bar").mismatch(setOf("type", "x", "y", "color")))

    @Test
    fun `lists fields the type doesn't allow and required fields that are missing`() {
        val mismatch = DctChartTypes.parse(patched).getValue("kpi").mismatch(setOf("type", "x", "color", "title"))!!
        assertEquals(listOf("color", "x"), mismatch.notAllowed)
        assertEquals(listOf("value"), mismatch.missing)
    }

    @Test
    fun `a schema without chart definitions yields no types`() =
        assertEquals(emptyMap<String, Any>(), DctChartTypes.parse("""{"${d}defs": {}}"""))
}
