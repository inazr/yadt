package com.dbthelper.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `cropBounds trims to the css range at 1x scale`() {
        val r = LineageScreenshotter.cropBounds(imageWidth = 1000, componentWidthCss = 1000, leftCss = 150.0, rightCss = 800.0)
        assertEquals(150, r!!.first)
        assertEquals(799, r.last) // 150 until 800 → 650px wide
    }

    @Test
    fun `cropBounds scales css to device px at 2x`() {
        val r = LineageScreenshotter.cropBounds(imageWidth = 2000, componentWidthCss = 1000, leftCss = 150.0, rightCss = 800.0)
        assertEquals(300, r!!.first)
        assertEquals(1599, r.last) // 300 until 1600
    }

    @Test
    fun `cropBounds clamps negative left to zero`() {
        val r = LineageScreenshotter.cropBounds(imageWidth = 1000, componentWidthCss = 1000, leftCss = -50.0, rightCss = 800.0)
        assertEquals(0, r!!.first)
        assertEquals(799, r.last)
    }

    @Test
    fun `cropBounds clamps right beyond image while still trimming left`() {
        val r = LineageScreenshotter.cropBounds(imageWidth = 1000, componentWidthCss = 1000, leftCss = 200.0, rightCss = 5000.0)
        assertEquals(200, r!!.first)
        assertEquals(999, r.last) // 200 until 1000
    }

    @Test
    fun `cropBounds returns null for full-width range`() {
        assertNull(LineageScreenshotter.cropBounds(imageWidth = 1000, componentWidthCss = 1000, leftCss = 0.0, rightCss = 1000.0))
    }

    @Test
    fun `cropBounds returns null for non-positive component width`() {
        assertNull(LineageScreenshotter.cropBounds(imageWidth = 1000, componentWidthCss = 0, leftCss = 100.0, rightCss = 200.0))
    }

    @Test
    fun `cropBounds returns null for empty range`() {
        assertNull(LineageScreenshotter.cropBounds(imageWidth = 1000, componentWidthCss = 1000, leftCss = 500.0, rightCss = 500.0))
    }
}
