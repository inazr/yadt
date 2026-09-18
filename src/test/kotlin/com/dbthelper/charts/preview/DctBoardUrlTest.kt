package com.dbthelper.charts.preview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DctBoardUrlTest {

    @Test
    fun `flat board maps to its stem`() = assertEquals("/sales/", DctBoardUrl.of("sales.yml"))

    @Test
    fun `yaml extension is stripped too`() = assertEquals("/sales/", DctBoardUrl.of("sales.yaml"))

    @Test
    fun `nested board keeps its directories`() = assertEquals("/fin/q1/", DctBoardUrl.of("fin/q1.yml"))

    @Test
    fun `spaces are percent-encoded, not plus`() = assertEquals("/fin/q%201/", DctBoardUrl.of("fin/q 1.yml"))

    @Test
    fun `non-ascii is utf-8 percent-encoded`() = assertEquals("/ums%C3%A4tze/", DctBoardUrl.of("umsätze.yml"))

    @Test
    fun `windows separators are normalised`() = assertEquals("/fin/q1/", DctBoardUrl.of("fin\\q1.yml"))
}
