package com.dbthelper.actions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefRelationDirectionTest {

    @Test
    fun `detects ref call`() {
        assertTrue(CopyWithRefsReplacedAction.containsJinjaRefs("select * from {{ ref('orders') }}"))
    }

    @Test
    fun `detects source call`() {
        assertTrue(CopyWithRefsReplacedAction.containsJinjaRefs("{{ source('raw', 'orders') }}"))
    }

    @Test
    fun `detects this`() {
        assertTrue(CopyWithRefsReplacedAction.containsJinjaRefs("from {{ this }}"))
    }

    @Test
    fun `plain relation is not a jinja ref`() {
        assertFalse(CopyWithRefsReplacedAction.containsJinjaRefs("analytics.public.orders"))
    }

    @Test
    fun `empty string is not a jinja ref`() {
        assertFalse(CopyWithRefsReplacedAction.containsJinjaRefs(""))
    }
}
