package com.dbthelper.toolwindow

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class LineageScreenshotterTest {

    private fun img(w: Int, h: Int): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `single variant is returned`() {
        val only = img(10, 10)
        assertSame(only, LineageScreenshotter.pickLargestVariant(listOf(only)))
    }

    @Test
    fun `largest by pixel area wins`() {
        val small = img(100, 100)   // 10_000 px
        val large = img(200, 200)   // 40_000 px
        assertSame(large, LineageScreenshotter.pickLargestVariant(listOf(small, large)))
    }

    @Test
    fun `first variant is kept on ties`() {
        val a = img(100, 100)
        val b = img(100, 100)
        assertSame(a, LineageScreenshotter.pickLargestVariant(listOf(a, b)))
    }

    @Test
    fun `empty list throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            LineageScreenshotter.pickLargestVariant(emptyList())
        }
    }
}
