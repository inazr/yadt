package com.dbthelper.charts.editor

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DctPalettesTest {

    @TempDir
    lateinit var pkg: Path

    private fun write(rel: String, text: String) {
        val file = pkg.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent())
    }

    private fun palettes(): DctPalettes {
        write("core/defaults/palettes/categorical/vivid-10.yml", """
            name: vivid-10
            colors: ["#111111", "#222222", "#333333"]
        """)
        write("core/defaults/palettes/categorical/editorial-10.yml", """
            name: editorial-10
            colors: ["#AAAAAA", "#BBBBBB"]
        """)
        write("core/defaults/palettes/scaffold/dbt-grays.yml", """
            name: dbt-grays
            colors: ["#FAFAFA", "#626366", "#222222"]
            aliases:
              canvas: 1
              muted: 2
              heading: 3
        """)
        write("core/defaults/palettes/tone/positive.yml", """
            name: positive
            aliases:
              solid: "#00AA00"
        """)
        write("core/defaults/themes/_base.yaml", """
            style:
              palettes:
                category: vivid-10
                chrome: dbt-grays
                positive: positive
              roles:
                ink: chrome.heading
        """)
        write("core/defaults/themes/editorial.yaml", """
            extends: _base
            style:
              palettes:
                category: editorial-10
        """)
        return DctPalettes.load(pkg)
    }

    @Test
    fun `reads palettes with family, colors and aliases`() {
        val p = palettes().palettes.getValue("dbt-grays")
        assertEquals("scaffold", p.family)
        assertEquals(listOf("#FAFAFA", "#626366", "#222222"), p.colors)
        assertEquals("2", p.aliases["muted"])
    }

    @Test
    fun `theme chain is base first and a theme overrides the base roles`() {
        val palettes = palettes()
        assertEquals("vivid-10", palettes.paletteRoles("_base")["category"])
        assertEquals("editorial-10", palettes.paletteRoles("editorial")["category"])
        assertEquals("dbt-grays", palettes.paletteRoles("editorial")["chrome"])
    }

    @Test
    fun `resolves every token form`() {
        val palettes = palettes()
        assertEquals("#222222", palettes.resolve("category[2]", "_base"))
        assertEquals("#BBBBBB", palettes.resolve("category[2]", "editorial"))
        assertEquals("#333333", palettes.resolve("vivid-10.3", "_base"))
        assertEquals("#626366", palettes.resolve("dbt-grays.muted", "_base"))
        assertEquals("#626366", palettes.resolve("chrome.muted", "_base"))
        assertEquals("#00AA00", palettes.resolve("positive.solid", "_base"))
        assertEquals("#222222", palettes.resolve("ink", "_base"))
        assertEquals("#123456", palettes.resolve("#123456", "_base"))
    }

    @Test
    fun `unknown tokens and a missing package resolve to nothing`() {
        assertNull(palettes().resolve("category[9]", "_base"))
        assertNull(palettes().resolve("nope.solid", "_base"))
        assertNull(DctPalettes.load(pkg.resolve("missing")).resolve("category[1]", "_base"))
    }
}
