package com.dbthelper.actions

import com.dbthelper.core.model.ManifestIndex
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File

/**
 * Reads dbt's `target/run_results.json` and produces an authoritative
 * { uniqueId -> status } map for the lineage cards.
 *
 * - Model / seed / snapshot results color their own node directly.
 * - Test results are NOT rolled up here: a model's bar color reflects its own
 *   build/run status only. Test outcomes are surfaced separately as the "!"
 *   triangle overlay (see LineageTab.pushRunResultsToJs), so a green-built model
 *   with a failing test stays green with a red triangle rather than turning red.
 * - When a node gets several contributions the worst status wins:
 *   error > warn > success > skipped.
 *
 * Statuses returned use the shared vocabulary: success | warn | error | skipped.
 */
object RunResultsReconciler {

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private val rank = mapOf("skipped" to 0, "success" to 1, "warn" to 2, "error" to 3)

    /** Map a raw dbt result status to our vocabulary, or null if unrecognized. */
    private fun mapStatus(raw: String): String? = when (raw.lowercase().trim()) {
        "success", "pass" -> "success"
        "warn" -> "warn"
        "error", "fail", "runtime error" -> "error"
        "skipped" -> "skipped"
        else -> null
    }

    /**
     * @param dbtRoot the dbt project root containing target/run_results.json
     * @return uniqueId -> status, or empty map if the file is missing/unparseable.
     */
    fun reconcile(dbtRoot: File, index: ManifestIndex): Map<String, String> {
        val file = File(dbtRoot, "target/run_results.json")
        if (!file.isFile) return emptyMap()

        val root = try {
            file.inputStream().use { mapper.readTree(it) }
        } catch (_: Exception) {
            return emptyMap()
        }
        val results = root.get("results") ?: return emptyMap()

        // Accumulate the worst status per node.
        val acc = mutableMapOf<String, String>()
        fun contribute(uniqueId: String, status: String) {
            val existing = acc[uniqueId]
            if (existing == null || (rank[status] ?: -1) > (rank[existing] ?: -1)) {
                acc[uniqueId] = status
            }
        }

        for (r in results) {
            val uniqueId = r.path("unique_id").asText(null) ?: continue
            // Tests aren't rendered as nodes and don't color a model's bar; their
            // outcomes are shown via the triangle overlay instead.
            if (isTestUniqueId(uniqueId)) continue
            val status = mapStatus(r.path("status").asText("")) ?: continue
            contribute(uniqueId, status)
        }
        return acc
    }

    val BUILDABLE_TYPES = setOf("model", "seed", "snapshot")
}
