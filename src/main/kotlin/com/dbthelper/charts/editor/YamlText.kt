package com.dbthelper.charts.editor

/** Pure text for Design edits: YAML scalar quoting and a nested key path to insert. */
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
        else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun nested(keys: List<String>, value: String, indent: Int, step: Int): String =
        keys.mapIndexed { level, key ->
            val pad = if (level == 0) "" else " ".repeat(indent + (level - 1) * step)
            if (level == keys.lastIndex) "$pad$key: ${scalar(value)}" else "$pad$key:"
        }.joinToString("\n")
}
