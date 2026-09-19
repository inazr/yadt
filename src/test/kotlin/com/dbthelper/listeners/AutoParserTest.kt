package com.dbthelper.listeners

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutoParserTest {

    @Test
    fun `parses with the target selected in YADT`() =
        assertEquals(listOf("dbt", "parse", "--target", "prod"), AutoParser.parseCommand("dbt", "prod"))

    @Test
    fun `no target selected leaves dbt's default`() =
        assertEquals(listOf("dbt", "parse"), AutoParser.parseCommand("dbt", ""))
}
