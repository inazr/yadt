package com.dbthelper.actions

import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.ManifestIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RunStatusOverlayTest {

    private fun node(id: String, type: String = "model", parents: List<String> = emptyList()) =
        DbtNode(id, id.substringAfterLast('.'), type, "p", "models/x.sql", "db", "Analytics", dependsOnNodes = parents)

    private fun result(id: String, status: RunStatus) = RunResult(id, status, null, 0.0, null, null)

    @Test
    fun `a failing test turns its nodes' triangle red, warnings alone keep it yellow`() {
        val index = ManifestIndex(nodes = listOf(
            node("model.p.a"), node("model.p.b"),
            node("test.p.t1", "test", listOf("model.p.a", "model.p.b")),
            node("test.p.t2", "test", listOf("model.p.a")),
            node("test.p.t3", "test", listOf("model.p.b")),
        ).associateBy { it.uniqueId })
        val results = listOf(
            result("model.p.a", RunStatus.SUCCESS),
            result("test.p.t1", RunStatus.WARN),
            result("test.p.t2", RunStatus.ERROR),
            result("test.p.t3", RunStatus.SUCCESS),
        ).associateBy { it.uniqueId }

        assertEquals(
            mapOf("model.p.a" to TestOutcome("error", failed = 1, warned = 1), "model.p.b" to TestOutcome("warn", failed = 0, warned = 1)),
            testOutcomesByNode(results, index)
        )
    }

    @Test
    fun `relation keys cover schema-qualified and fully qualified names of buildable nodes`() {
        val index = ManifestIndex(nodes = listOf(node("model.p.orders"), node("test.p.t1", "test")).associateBy { it.uniqueId })
        assertEquals(
            mapOf("analytics.orders" to "model.p.orders", "db.analytics.orders" to "model.p.orders"),
            DbtRunStatusParser.relationKeyIndex(index)
        )
    }
}
