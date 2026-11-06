package com.dbthelper.charts.editor

/** Pure text for Design edits: YAML scalar quoting, flow lists and a nested key path to insert. */
object YamlText {

    private val PLAIN = Regex("""[A-Za-z0-9_\-.\[\]\s]*[A-Za-z0-9_\-.\]]""")

    // Leading characters that are YAML structural indicators (sequence/flow markers, comments,
    // anchors/aliases, tags, block scalars, quotes, directives) and would change meaning if left
    // unquoted, even though some of them (`[`, `-`) are otherwise allowed inside PLAIN.
    private val LEADING_INDICATORS = setOf('[', '{', '-', '#', '&', '*', '!', '|', '>', '\'', '"', '%', '@')

    fun scalar(value: String): String =
        if (value.isNotEmpty() && value.trim() == value && value.first() !in LEADING_INDICATORS &&
            !value.contains(": ") && !value.contains(" #") && PLAIN.matches(value)
        ) value
        else quoted(value)

    /** The items of a comma-separated list field; blank items are dropped. */
    fun items(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** A flow sequence (`[a, b]`); brackets end a flow item, so items holding one are quoted. */
    fun list(items: List<String>): String =
        items.joinToString(", ", "[", "]") { if (it.any { c -> c in "[]{}" }) quoted(it) else scalar(it) }

    /** [rendered] is the value's YAML text, e.g. from [scalar] or [list]. */
    fun nested(keys: List<String>, rendered: String, indent: Int, step: Int): String =
        keys.mapIndexed { level, key ->
            val pad = if (level == 0) "" else " ".repeat(indent + (level - 1) * step)
            if (level == keys.lastIndex) "$pad$key: $rendered" else "$pad$key:"
        }.joinToString("\n")

    private fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
