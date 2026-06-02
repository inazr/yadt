package com.dbthelper.toolwindow.selector

import com.dbthelper.core.model.ManifestIndex

/** Pre-built, de-duplicated, sorted pools the autocomplete fuzzy-matches against. */
data class SelectorCandidates(
    val models: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val fqns: List<String> = emptyList()
) {
    companion object {
        val EMPTY = SelectorCandidates()

        // Bare names resolve buildable nodes; tests/analyses would be noise in the popup.
        private val NAMED_TYPES = setOf("model", "seed", "snapshot")

        fun from(index: ManifestIndex): SelectorCandidates {
            val models = index.nodes.values
                .filter { it.resourceType in NAMED_TYPES }
                .map { it.name }
                .distinct().sorted()

            val tags = (index.nodes.values.flatMap { it.tags } +
                index.sources.values.flatMap { it.tags } +
                index.exposures.values.flatMap { it.tags })
                .distinct().sorted()

            val sources = index.sources.values
                .map { "${it.sourceName}.${it.name}" }
                .distinct().sorted()

            val paths = (index.nodes.values.map { it.originalFilePath } +
                index.sources.values.map { it.originalFilePath })
                .flatMap { dirPrefixes(it) }
                .distinct().sorted()

            // Same node-type scope as [models]: an fqn: selector targets buildable nodes,
            // so test/analysis fqns would only be noise in the popup.
            val fqns = index.nodes.values
                .filter { it.resourceType in NAMED_TYPES && it.fqn.isNotEmpty() }
                .map { it.fqn.joinToString(".") }
                .distinct().sorted()

            return SelectorCandidates(models, tags, sources, paths, fqns)
        }

        /** Every directory prefix of a path: `models/staging/x.sql` -> [`models`, `models/staging`]. */
        private fun dirPrefixes(filePath: String): List<String> {
            val parts = filePath.replace('\\', '/').split('/').dropLast(1)
            val out = ArrayList<String>(parts.size)
            val sb = StringBuilder()
            for (p in parts) {
                if (p.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append('/')
                sb.append(p)
                out += sb.toString()
            }
            return out
        }
    }
}
