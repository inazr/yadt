package com.dbthelper.charts.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class YamlTextTest {

    @Test
    fun `plain scalars stay unquoted, the rest is quoted`() {
        assertEquals("Delivered volume", YamlText.scalar("Delivered volume"))
        assertEquals("category[2]", YamlText.scalar("category[2]"))
        assertEquals("600", YamlText.scalar("600"))
        assertEquals("true", YamlText.scalar("true"))
        assertEquals("\"#FFFFFF\"", YamlText.scalar("#FFFFFF"))
        assertEquals("\"a: b\"", YamlText.scalar("a: b"))
        assertEquals("\"\"", YamlText.scalar(""))
    }

    @Test
    fun `values that would change YAML structure are quoted even when otherwise plain`() {
        assertEquals("\"[2]\"", YamlText.scalar("[2]"))
        assertEquals("\"- a\"", YamlText.scalar("- a"))
    }

    @Test
    fun `nested keys are indented below the mapping's indent`() {
        assertEquals("style:\n    color:\n      static: category[2]", YamlText.nested(listOf("style", "color", "static"), "category[2]", indent = 4, step = 2))
        assertEquals("title: Hi", YamlText.nested(listOf("title"), "Hi", indent = 0, step = 2))
    }

    @Test
    fun `a comma-separated list becomes a flow sequence`() {
        assertEquals(listOf("finance", "q1"), YamlText.items(" finance, ,q1 "))
        assertEquals(emptyList<String>(), YamlText.items(" , "))
        assertEquals("[finance, 4, \"#fff\"]", YamlText.list(listOf("finance", "4", "#fff")))
        // Brackets are plain in a block scalar but end a flow sequence.
        assertEquals("[\"category[2]\"]", YamlText.list(listOf("category[2]")))
    }
}
