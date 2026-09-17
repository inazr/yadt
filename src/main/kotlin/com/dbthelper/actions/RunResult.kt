package com.dbthelper.actions

import com.dbthelper.core.model.ManifestIndex
import java.time.Instant

enum class RunStatus(val wire: String) {
    SUCCESS("success"),
    ERROR("error"),
    WARN("warn"),
    SKIPPED("skipped"),
    QUEUED("queued"),
    RUNNING("running");

    companion object {
        /** A `run_results.json` status in our vocabulary, or null for one we don't color (e.g. `no-op`). */
        fun fromDbtStatus(raw: String): RunStatus? = when (raw.lowercase().trim()) {
            "success", "pass" -> SUCCESS
            "error", "fail", "runtime error" -> ERROR
            "warn" -> WARN
            "skipped" -> SKIPPED
            else -> null
        }
    }
}

/**
 * True for data-test and unit-test unique_ids. These aren't graph nodes: they color no
 * card and don't count toward the "last run" node total — their outcomes surface via the
 * "!" triangle overlay instead.
 */
fun isTestUniqueId(uniqueId: String): Boolean =
    uniqueId.startsWith("test.") || uniqueId.startsWith("unit_test.")

/**
 * uniqueId -> status wire value for the lineage cards. Tests color no card (their outcomes
 * surface via the "!" triangle overlay), so a green model with a failing test stays green.
 */
fun nodeStatuses(results: Map<String, RunResult>): Map<String, String> =
    results.filterKeys { !isTestUniqueId(it) }.mapValues { it.value.status.wire }

/** A node's "!" triangle: red if any of its tests errored, else yellow; with the counts behind it. */
data class TestOutcome(val status: String, val failed: Int, val warned: Int)

/**
 * Attribute test results to the nodes they validate, for the "!" triangle overlay: each errored or
 * warned test counts against every node it depends on. Nodes without such a test are absent.
 */
fun testOutcomesByNode(results: Map<String, RunResult>, index: ManifestIndex): Map<String, TestOutcome> {
    val failed = HashMap<String, Int>()
    val warned = HashMap<String, Int>()
    for ((id, r) in results) {
        if (!isTestUniqueId(id)) continue
        val bucket = when (r.status) {
            RunStatus.ERROR -> failed
            RunStatus.WARN -> warned
            else -> continue
        }
        // parentMap excludes tests, so read the tested nodes off the test node itself.
        for (parentId in index.nodes[id]?.dependsOnNodes.orEmpty()) {
            bucket[parentId] = (bucket[parentId] ?: 0) + 1
        }
    }
    return (failed.keys + warned.keys).associateWith { nodeId ->
        val f = failed[nodeId] ?: 0
        TestOutcome(status = if (f > 0) "error" else "warn", failed = f, warned = warned[nodeId] ?: 0)
    }
}

data class RunResult(
    val uniqueId: String,
    val status: RunStatus,
    val message: String?,
    val executionTime: Double,
    val startedAt: Instant?,
    val failures: Int?
)
