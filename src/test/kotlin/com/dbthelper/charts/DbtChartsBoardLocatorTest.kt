package com.dbthelper.charts

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DbtChartsBoardLocatorTest {

    @TempDir
    lateinit var tmp: Path

    private fun file(rel: String): Path =
        tmp.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, "") }

    private fun isBoard(path: Path) = DbtChartsBoardLocator.isBoard(
        path.fileName.toString(), path.parent,
        { it.parent }, { it.fileName?.toString() ?: "" }, { dir, name -> Files.isRegularFile(dir.resolve(name)) },
    )

    @Test
    fun `yml directly under charts of a dbt charts project is a board`() {
        file("proj/dbt_charts.yml")
        assertTrue(isBoard(file("proj/charts/sales.yml")))
    }

    @Test
    fun `yaml nested below charts is a board`() {
        file("proj/dbt_charts.yml")
        assertTrue(isBoard(file("proj/charts/general/kpi.yaml")))
    }

    @Test
    fun `meta yml is a cascade file, not a board`() {
        file("proj/dbt_charts.yml")
        assertFalse(isBoard(file("proj/charts/meta.yml")))
        assertFalse(isBoard(file("proj/charts/general/meta.yaml")))
    }

    @Test
    fun `charts dir without a sibling dbt_charts yml is not a board dir`() {
        assertFalse(isBoard(file("proj/charts/sales.yml")))
    }

    @Test
    fun `charts dir nested under models of a charts project does not count`() {
        file("proj/dbt_charts.yml")
        assertFalse(isBoard(file("proj/models/charts/sales.yml")))
    }

    @Test
    fun `non yaml files are never boards`() {
        file("proj/dbt_charts.yml")
        assertFalse(isBoard(file("proj/charts/data.csv")))
        assertFalse(isBoard(file("proj/charts/query.sql")))
    }
}
