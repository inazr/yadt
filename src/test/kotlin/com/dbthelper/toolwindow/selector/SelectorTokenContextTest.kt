package com.dbthelper.toolwindow.selector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelectorTokenContextTest {

    @Test
    fun `bare single token`() {
        val ctx = SelectorTokenContext.parse("stg_or", caret = 6)
        assertEquals("stg_or", ctx.query)
        assertEquals(SelectorCategory.BARE, ctx.category)
        assertEquals(0, ctx.replaceStart)
        assertEquals(6, ctx.replaceEnd)
    }

    @Test
    fun `caret token within a space-separated union`() {
        val text = "stg_orders stg_cu"
        val ctx = SelectorTokenContext.parse(text, caret = text.length)
        assertEquals("stg_cu", ctx.query)
        assertEquals(SelectorCategory.BARE, ctx.category)
        assertEquals(11, ctx.replaceStart)
        assertEquals(17, ctx.replaceEnd)
    }

    @Test
    fun `leading and trailing graph operators are stripped from a bare token`() {
        val text = "2+ord+1"
        val ctx = SelectorTokenContext.parse(text, caret = text.length)
        assertEquals("ord", ctx.query)
        assertEquals(SelectorCategory.BARE, ctx.category)
        assertEquals(2, ctx.replaceStart)
        assertEquals(5, ctx.replaceEnd)
    }

    @Test
    fun `tag prefix yields TAG category and value-only query`() {
        val text = "tag:dai"
        val ctx = SelectorTokenContext.parse(text, caret = text.length)
        assertEquals("dai", ctx.query)
        assertEquals(SelectorCategory.TAG, ctx.category)
        assertEquals(4, ctx.replaceStart)
        assertEquals(7, ctx.replaceEnd)
    }

    @Test
    fun `source prefix keeps a plus inside the value`() {
        val text = "source:raw.or+"
        val ctx = SelectorTokenContext.parse(text, caret = text.length)
        assertEquals("raw.or+", ctx.query)
        assertEquals(SelectorCategory.SOURCE, ctx.category)
        assertEquals(7, ctx.replaceStart)
        assertEquals(14, ctx.replaceEnd)
    }

    @Test
    fun `leading operator before a method prefix is allowed`() {
        val text = "+tag:fo"
        val ctx = SelectorTokenContext.parse(text, caret = text.length)
        assertEquals("fo", ctx.query)
        assertEquals(SelectorCategory.TAG, ctx.category)
        assertEquals(5, ctx.replaceStart)
        assertEquals(7, ctx.replaceEnd)
    }

    @Test
    fun `unknown method prefix is treated as a bare token`() {
        val text = "config:x"
        val ctx = SelectorTokenContext.parse(text, caret = text.length)
        assertEquals("config:x", ctx.query)
        assertEquals(SelectorCategory.BARE, ctx.category)
        assertEquals(0, ctx.replaceStart)
        assertEquals(8, ctx.replaceEnd)
    }

    @Test
    fun `a lone plus operator yields an empty query and a non-inverted range`() {
        val ctx = SelectorTokenContext.parse("+", caret = 1)
        assertEquals("", ctx.query)
        assertEquals(SelectorCategory.BARE, ctx.category)
        assertEquals(ctx.replaceStart, ctx.replaceEnd) // empty range, never inverted
        assertEquals(1, ctx.replaceStart)
    }

    @Test
    fun `double plus operators yield an empty non-inverted range`() {
        val ctx = SelectorTokenContext.parse("++", caret = 2)
        assertEquals("", ctx.query)
        assertEquals(SelectorCategory.BARE, ctx.category)
        assertEquals(ctx.replaceStart, ctx.replaceEnd)
    }
}
