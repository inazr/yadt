package com.dbthelper.listeners

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutoParseOnSaveListenerTest {

    @Test
    fun `parses with the target selected in YADT`() =
        assertEquals(listOf("dbt", "parse", "--target", "prod"), AutoParseOnSaveListener.parseCommand("dbt", "prod"))

    @Test
    fun `no target selected leaves dbt's default`() =
        assertEquals(listOf("dbt", "parse"), AutoParseOnSaveListener.parseCommand("dbt", ""))
}
