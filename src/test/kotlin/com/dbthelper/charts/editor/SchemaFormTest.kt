package com.dbthelper.charts.editor

import com.dbthelper.core.jsonMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaFormTest {

    private val d = "$"
    private val schema = jsonMapper.readTree(
        """
        {"properties": {
           "title": {"type": "string", "description": "Heading."},
           "theme": {"anyOf": [{"type": "string"}, {"type": "null"}]},
           "style": {"anyOf": [{"${d}ref": "#/${d}defs/Style"}, {"type": "null"}]}
         },
         "${d}defs": {
           "AuthoredChart": {"anyOf": [{"${d}ref": "#/${d}defs/PieChart"}, {"${d}ref": "#/${d}defs/PointMapChart"}]},
           "PieChart": {"additionalProperties": false, "properties": {
              "type": {"enum": ["pie", "donut"]},
              "theta": {"type": "string", "description": "Column for angular encoding."},
              "color": {"anyOf": [{"type": "string"}, {"type": "null"}], "description": "Column naming each wedge."},
              "x_label": {"type": "string", "description": "Title from the x column's name."},
              "title": {"type": "string"},
              "height": {"anyOf": [{"type": "integer"}, {"type": "null"}]},
              "tags": {"type": "array"},
              "style": {"${d}ref": "#/${d}defs/PieStyle"}
           }},
           "PointMapChart": {"additionalProperties": false, "properties": {
              "type": {"enum": ["point_map"]}, "title": {"type": "string"}
           }},
           "PieStyle": {"properties": {
              "background": {"anyOf": [{"type": "string"}, {"type": "null"}], "description": "Chart background."},
              "size": {"${d}ref": "#/${d}defs/Size"},
              "total": {"${d}ref": "#/${d}defs/Total"}
           }},
           "Size": {"properties": {"width": {"type": "number"}, "height": {"type": "number"}}},
           "Total": {"properties": {
              "show": {"type": "boolean"}, "label": {"type": "string"}, "a": {"type": "string"},
              "b": {"type": "string"}, "c": {"type": "string"}
           }},
           "Style": {"properties": {"max_width": {"type": "number"}, "loop": {"${d}ref": "#/${d}defs/Style"}}}
        }}
        """,
    )

    private fun Form.field(name: String) = groups.flatMap { it.fields }.first { it.name == name }

    private fun Form.group(title: String) = groups.first { it.title == title }

    private fun Form.names() = groups.associate { it.title to it.fields.map(FormField::name) }

    @Test
    fun `chart header and groups`() {
        val form = SchemaForm.forChart(schema, "donut")
        assertEquals("Donut Chart", form.title)
        assertEquals("pie", form.family)
        assertEquals(
            mapOf(
                "Content" to listOf("title"),
                "Encoding" to listOf("theta", "color", "x_label"),
                "Size" to listOf("height"),
                "Style" to listOf("background", "size", "total"),
                "Advanced" to listOf("tags"),
            ),
            form.names(),
        )
        assertEquals(listOf("Content", "Encoding"), form.groups.filter { it.expanded }.map { it.title })
        assertEquals(FieldKind.Text, form.field("x_label").kind)
        assertEquals(FieldKind.Number, form.field("height").kind)
        assertTrue(form.field("tags").kind is FieldKind.Unsupported)
    }

    @Test
    fun `the Style group holds the style section's fields, compact rows, colours and toggles`() {
        val (background, size, total) = SchemaForm.forChart(schema, "pie").group("Style").fields
        assertEquals(FieldKind.Color, background.kind)
        assertEquals(listOf("style", "background"), background.path)
        assertEquals(listOf("width", "height"), (size.kind as FieldKind.Compact).children.map { it.name })
        val totalSection = total.kind as FieldKind.Section
        assertEquals("show", totalSection.toggle?.name)
        assertEquals(listOf("label", "a", "b", "c"), totalSection.children.map { it.name })
    }

    @Test
    fun `board form, labels and recursion guard`() {
        val form = SchemaForm.forBoard(schema)
        assertEquals("Board", form.title)
        assertNull(form.family)
        assertEquals(mapOf("Content" to listOf("title"), "Appearance" to listOf("theme", "style")), form.names())
        assertEquals(listOf("Content"), form.groups.filter { it.expanded }.map { it.title })
        val style = form.field("style").kind as FieldKind.Section
        assertTrue(style.children.first { it.name == "loop" }.kind is FieldKind.Unsupported)
        assertEquals("Max width", SchemaForm.label("max_width"))
    }

    @Test
    fun `fields follow dct's declared order, nested definitions included`() {
        val order = DctFieldOrder.parse(
            """
            ## PieChart
            | `height` |
            | `color` |
            | `theta` |
            ## PieStyle
            | `total` |
            | `background` |
            """.trimIndent(),
        )
        val form = SchemaForm.forChart(schema, "pie", order)
        assertEquals(listOf("color", "theta", "x_label"), form.group("Encoding").fields.map { it.name })
        assertEquals(listOf("total", "background", "size"), form.group("Style").fields.map { it.name })
    }

    @Test
    fun `a field no group names lands in Advanced`() {
        val extra = jsonMapper.readTree("""{"properties": {"title": {"type": "string"}, "brand_new": {"type": "string"}}}""")
        assertEquals(mapOf("Content" to listOf("title"), "Advanced" to listOf("brand_new")), SchemaForm.forBoard(extra).names())
    }

    @Test
    fun `an unknown chart type gets the fields all types share`() {
        assertEquals(mapOf("Content" to listOf("title")), SchemaForm.forChart(schema, null).names())
    }

    @Test
    fun `a link field is not mistaken for a colour field`() {
        val linkSchema = jsonMapper.readTree(
            """{"properties": {"link": {"type": "string", "description": "Where the chart links to."}}}""",
        )
        assertEquals(FieldKind.Text, SchemaForm.forBoard(linkSchema).field("link").kind)
    }

    @Test
    fun `anyOf of integer and number becomes a Number field`() {
        val widthSchema = jsonMapper.readTree(
            """{"properties": {"width": {"anyOf": [{"type": "integer"}, {"type": "number"}, {"type": "null"}]}}}""",
        )
        assertEquals(FieldKind.Number, SchemaForm.forBoard(widthSchema).field("width").kind)
    }
}
