package com.dbthelper.core

import com.dbthelper.core.model.DbtColumn
import com.dbthelper.core.model.DbtExposure
import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.ManifestIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalColumnAnalyzerTest {

    private fun model(
        id: String,
        name: String,
        columns: List<String> = emptyList(),
        compiled: String? = null,
    ) = DbtNode(
        uniqueId = id, name = name, resourceType = "model",
        packageName = "proj", originalFilePath = "models/$name.sql",
        columns = columns.associateWith { DbtColumn(name = it) },
        compiledCode = compiled,
    )

    @Test
    fun `column not referenced by any child is terminal`() {
        val a = model("model.proj.a", "a", columns = listOf("id", "secret"))
        val b = model("model.proj.b", "b", compiled = "select id from a")
        val index = ManifestIndex(
            nodes = mapOf(a.uniqueId to a, b.uniqueId to b),
            childMap = mapOf(a.uniqueId to listOf(b.uniqueId)),
        )
        assertEquals(setOf("secret"), TerminalColumnAnalyzer.computeTerminalColumns(a, index))
    }

    @Test
    fun `select-star child consumes all columns`() {
        val a = model("model.proj.a", "a", columns = listOf("id", "secret"))
        val b = model("model.proj.b", "b", compiled = "select * from a")
        val index = ManifestIndex(
            nodes = mapOf(a.uniqueId to a, b.uniqueId to b),
            childMap = mapOf(a.uniqueId to listOf(b.uniqueId)),
        )
        assertEquals(emptySet<String>(), TerminalColumnAnalyzer.computeTerminalColumns(a, index))
    }

    @Test
    fun `exposure dependency consumes all columns`() {
        val a = model("model.proj.a", "a", columns = listOf("id", "secret"))
        val exposure = DbtExposure(
            uniqueId = "exposure.proj.dash", name = "dash", type = "dashboard",
            packageName = "proj", originalFilePath = "models/exposures.yml",
            dependsOnNodes = listOf("model.proj.a"),
        )
        val index = ManifestIndex(
            nodes = mapOf(a.uniqueId to a),
            exposures = mapOf(exposure.uniqueId to exposure),
        )
        assertEquals(emptySet<String>(), TerminalColumnAnalyzer.computeTerminalColumns(a, index))
    }

    @Test
    fun `model with no documented columns yields empty`() {
        val a = model("model.proj.a", "a", columns = emptyList())
        val index = ManifestIndex(nodes = mapOf(a.uniqueId to a))
        assertEquals(emptySet<String>(), TerminalColumnAnalyzer.computeTerminalColumns(a, index))
    }

    @Test
    fun `leaf model with no children flags all columns`() {
        val a = model("model.proj.a", "a", columns = listOf("id", "secret"))
        val index = ManifestIndex(nodes = mapOf(a.uniqueId to a))
        assertEquals(setOf("id", "secret"), TerminalColumnAnalyzer.computeTerminalColumns(a, index))
    }

    @Test
    fun `qualified star as a non-first select term consumes all columns`() {
        val a = model("model.proj.a", "a", columns = listOf("id", "secret"))
        val b = model("model.proj.b", "b", compiled = "select b.id, a.* from a join b on a.id = b.id")
        val index = ManifestIndex(
            nodes = mapOf(a.uniqueId to a, b.uniqueId to b),
            childMap = mapOf(a.uniqueId to listOf(b.uniqueId)),
        )
        assertEquals(emptySet<String>(), TerminalColumnAnalyzer.computeTerminalColumns(a, index))
    }

    @Test
    fun `falls back to rawCode when compiledCode is absent`() {
        val a = model("model.proj.a", "a", columns = listOf("id", "secret"))
        val b = DbtNode(
            uniqueId = "model.proj.b", name = "b", resourceType = "model",
            packageName = "proj", originalFilePath = "models/b.sql",
            compiledCode = null, rawCode = "select id from a",
        )
        val index = ManifestIndex(
            nodes = mapOf(a.uniqueId to a, b.uniqueId to b),
            childMap = mapOf(a.uniqueId to listOf(b.uniqueId)),
        )
        assertEquals(setOf("secret"), TerminalColumnAnalyzer.computeTerminalColumns(a, index))
    }
}
