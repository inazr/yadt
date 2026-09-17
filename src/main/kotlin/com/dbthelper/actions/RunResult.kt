package com.dbthelper.actions

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

data class RunResult(
    val uniqueId: String,
    val status: RunStatus,
    val message: String?,
    val executionTime: Double,
    val startedAt: Instant?,
    val failures: Int?
)
