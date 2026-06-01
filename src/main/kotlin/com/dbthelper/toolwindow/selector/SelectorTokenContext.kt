package com.dbthelper.toolwindow.selector

enum class SelectorCategory { BARE, TAG, SOURCE, PATH, FQN }

/**
 * The token under the caret in a dbt selector field, decomposed for autocomplete.
 *
 * dbt selectors are space/comma-separated tokens, each optionally wrapped in graph operators
 * (`+`, `N+`, `+N`) and optionally carrying a method prefix (`tag:`, `source:`, `path:`,
 * `fqn:`). This isolates the *matchable body* of the token the caret sits in, plus the
 * [replaceStart]..[replaceEnd] range to splice a chosen suggestion into — leaving operators,
 * the prefix, and all sibling tokens untouched.
 */
data class SelectorTokenContext(
    val query: String,
    val category: SelectorCategory,
    val replaceStart: Int,
    val replaceEnd: Int
) {
    companion object {
        private val LEADING_OP = Regex("^\\d*\\+")
        private val TRAILING_OP = Regex("\\+\\d*$")
        private val METHODS = mapOf(
            "tag" to SelectorCategory.TAG,
            "source" to SelectorCategory.SOURCE,
            "path" to SelectorCategory.PATH,
            "fqn" to SelectorCategory.FQN
        )

        private fun isSep(c: Char) = c == ' ' || c == ','

        /**
         * Parse the token under [caret] (clamped to `0..text.length`) in [text].
         */
        fun parse(text: String, caret: Int): SelectorTokenContext {
            val c = caret.coerceIn(0, text.length)
            var start = c
            while (start > 0 && !isSep(text[start - 1])) start--
            var end = c
            while (end < text.length && !isSep(text[end])) end++

            // Strip a leading graph operator (`+` or `N+`); dbt allows e.g. `+tag:foo`.
            val token = text.substring(start, end)
            val lead = LEADING_OP.find(token)?.value?.length ?: 0
            val bodyStart = start + lead

            val afterLead = text.substring(bodyStart, end)
            val colon = afterLead.indexOf(':')
            if (colon >= 0) {
                val method = METHODS[afterLead.substring(0, colon)]
                if (method != null) {
                    // Method value: a `+` here belongs to the value, so do NOT strip a trailing op.
                    val valueStart = bodyStart + colon + 1
                    return SelectorTokenContext(
                        query = text.substring(valueStart, end),
                        category = method,
                        replaceStart = valueStart,
                        replaceEnd = end
                    )
                }
            }
            // Bare token: also strip a trailing graph operator.
            val tail = TRAILING_OP.find(afterLead)?.value?.length ?: 0
            val bodyEnd = end - tail
            return SelectorTokenContext(
                query = text.substring(bodyStart, bodyEnd),
                category = SelectorCategory.BARE,
                replaceStart = bodyStart,
                replaceEnd = bodyEnd
            )
        }
    }
}
