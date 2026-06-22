package com.dbthelper.core

import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.ManifestIndex

/**
 * Computes the set of a model's output columns that are not consumed by any
 * downstream model or exposure ("potentially terminal"). Heuristic, conservative:
 * errs toward "consumed" so false terminal flags stay rare.
 *
 * Only direct children + exposures are inspected: a column leaves a model only
 * through its direct children, so deeper graph nodes cannot consume it on their own.
 */
object TerminalColumnAnalyzer {

    // "select *" or qualified "*" (e.g., "a.*") anywhere in SQL => treat all columns consumed.
    private val SELECT_STAR = Regex("""select\s+\*|\b\w+\s*\.\s*\*""", RegexOption.IGNORE_CASE)

    fun computeTerminalColumns(node: DbtNode, index: ManifestIndex): Set<String> {
        val columns = node.columns.keys
        if (columns.isEmpty()) return emptySet()

        // (1) Any exposure depending on this node => all columns consumed.
        if (index.exposures.values.any { node.uniqueId in it.dependsOnNodes }) return emptySet()

        val children = index.getDownstream(node.uniqueId).mapNotNull { index.nodes[it] }
        val childSqls = children.map { it.compiledCode ?: it.rawCode ?: "" }

        // (2) Any child selecting * => all columns consumed.
        if (childSqls.any { SELECT_STAR.containsMatchIn(it) }) return emptySet()

        // (3) A column is consumed if its name appears as an identifier token in any child's SQL.
        return columns.filterNot { col ->
            val token = Regex("""\b${Regex.escape(col)}\b""", RegexOption.IGNORE_CASE)
            childSqls.any { token.containsMatchIn(it) }
        }.toSet()
    }
}
