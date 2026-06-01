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
        fun fromDbtStatus(raw: String): RunStatus = when (raw.lowercase()) {
            "success", "pass" -> SUCCESS
            "error", "fail", "runtime error" -> ERROR
            "warn" -> WARN
            "skipped" -> SKIPPED
            else -> ERROR
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

data class RunResult(
    val uniqueId: String,
    val status: RunStatus,
    val message: String?,
    val executionTime: Double,
    val startedAt: Instant?,
    val failures: Int?
)
