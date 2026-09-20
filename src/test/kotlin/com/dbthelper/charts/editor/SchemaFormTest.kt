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

    private fun Form.field(name: String) = (encoding + general + sections).first { it.name == name }

    @Test
    fun `chart header, encoding and general fields`() {
        val form = SchemaForm.forChart(schema, "donut")
        assertEquals("Donut Chart", form.title)
        assertEquals("pie", form.family)
        assertEquals(listOf("theta", "color"), form.encoding.map { it.name })
        assertEquals(listOf("x_label", "title", "height", "tags"), form.general.map { it.name })
        assertEquals(FieldKind.Text, form.field("x_label").kind)
        assertEquals(FieldKind.Number, form.field("height").kind)
        assertTrue(form.field("tags").kind is FieldKind.Unsupported)
    }

    @Test
    fun `sections, compact rows, colours and toggles`() {
        val style = SchemaForm.forChart(schema, "pie").field("style").kind as FieldKind.Section
        val (background, size, total) = style.children
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
        assertEquals(listOf("title", "theme"), form.general.map { it.name })
        val style = form.field("style").kind as FieldKind.Section
        assertTrue(style.children.first { it.name == "loop" }.kind is FieldKind.Unsupported)
        assertEquals("Max width", SchemaForm.label("max_width"))
    }

    @Test
    fun `an unknown chart type gets the fields all types share`() {
        assertEquals(listOf("title"), SchemaForm.forChart(schema, null).general.map { it.name })
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
