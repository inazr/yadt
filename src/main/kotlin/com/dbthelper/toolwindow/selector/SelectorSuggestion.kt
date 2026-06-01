package com.dbthelper.toolwindow.selector

import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil

/** One row in the autocomplete popup. [text] is both shown and spliced into the field. */
data class SelectorSuggestion(val text: String)

private const val MAX_SUGGESTIONS = 50

/** Method prefixes offered for bare tokens so users discover the richer grammar. */
private val BARE_PREFIXES = listOf("tag:", "source:", "path:", "fqn:")

/**
 * Fuzzy-rank suggestions for [context] against [candidates]. Pure — no project/EDT, so it
 * is unit-tested directly. BARE tokens get model names PLUS the method prefixes; a prefixed
 * token gets only that method's pool. Ranking uses IntelliJ's [MinusculeMatcher]; the leading
 * `*` makes matching contains-style rather than anchored at the start.
 */
fun rankSelectorSuggestions(
    context: SelectorTokenContext,
    candidates: SelectorCandidates
): List<SelectorSuggestion> {
    val pool: List<String> = when (context.category) {
        SelectorCategory.BARE -> candidates.models + BARE_PREFIXES
        SelectorCategory.TAG -> candidates.tags
        SelectorCategory.SOURCE -> candidates.sources
        SelectorCategory.PATH -> candidates.paths
        SelectorCategory.FQN -> candidates.fqns
    }
    if (context.query.isEmpty()) {
        return pool.take(MAX_SUGGESTIONS).map { SelectorSuggestion(it) }
    }
    val matcher: MinusculeMatcher = NameUtil.buildMatcher("*${context.query}").build()
    return pool.asSequence()
        .filter { matcher.matches(it) }
        .sortedWith(compareByDescending<String> { matcher.matchingDegree(it) }.thenBy { it })
        .take(MAX_SUGGESTIONS)
        .map { SelectorSuggestion(it) }
        .toList()
}
