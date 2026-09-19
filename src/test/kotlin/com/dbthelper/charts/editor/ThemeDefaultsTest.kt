package com.dbthelper.charts.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThemeDefaultsTest {

    private val palettes = DctPalettes(
        palettes = emptyMap(),
        themes = mapOf(
            "_base" to mapOf("style" to mapOf(
                "background" to "#FFFFFF",
                "font" to mapOf("size" to 11, "family" to "Inter Variable"),
                "charts" to mapOf("padding" to 4, "bar" to mapOf("padding" to 8)),
            )),
            "clarity" to mapOf("extends" to "_base", "style" to mapOf("font" to mapOf("family" to "Source Serif 4"))),
            "neon" to mapOf("extends" to "_base", "style" to mapOf("background" to "#000000")),
        ),
    )

    @Test
    fun `defaults to clarity over the base`() {
        val d = ThemeDefaults(palettes, boardTheme = null, metaDocs = emptyList())
        assertEquals("clarity", d.theme)
        assertEquals("Source Serif 4", d.board(listOf("style", "font", "family")))
        assertEquals("11", d.board(listOf("style", "font", "size")))
        assertEquals("clarity", d.board(listOf("theme")))
    }

    @Test
    fun `board theme and meta files override`() {
        val meta = listOf(mapOf("theme" to "neon"), mapOf("style" to mapOf("font" to mapOf("size" to 13))))
        val d = ThemeDefaults(palettes, boardTheme = null, metaDocs = meta)
        assertEquals("neon", d.theme)
        assertEquals("#000000", d.board(listOf("style", "background")))
        assertEquals("13", d.board(listOf("style", "font", "size")))
        assertEquals("_base", ThemeDefaults(palettes, boardTheme = "_base", metaDocs = meta).theme)
    }

    @Test
    fun `chart style looks up family, then charts, then board style`() {
        val d = ThemeDefaults(palettes, boardTheme = null, metaDocs = emptyList())
        assertEquals("8", d.chart("bar", listOf("style", "padding")))
        assertEquals("4", d.chart("line", listOf("style", "padding")))
        assertEquals("#FFFFFF", d.chart("line", listOf("style", "background")))
    }

    @Test
    fun `non-style chart fields and objects have no inherited value`() {
        val d = ThemeDefaults(palettes, boardTheme = null, metaDocs = emptyList())
        assertNull(d.chart("bar", listOf("title")))
        assertNull(d.board(listOf("style", "font")))
        assertNull(d.board(listOf("style", "missing")))
    }
}
