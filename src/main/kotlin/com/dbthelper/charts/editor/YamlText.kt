package com.dbthelper.charts.editor

/** Pure text for Design edits: YAML scalar quoting and a nested key path to insert. */
object YamlText {

    private val PLAIN = Regex("""[A-Za-z0-9_\-.\[\]\s]*[A-Za-z0-9_\-.\]]""")

    fun scalar(value: String): String =
        if (value.isNotEmpty() && PLAIN.matches(value) && value.trim() == value) value
        else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun nested(keys: List<String>, value: String, indent: Int, step: Int): String =
        keys.mapIndexed { level, key ->
            val pad = if (level == 0) "" else " ".repeat(indent + (level - 1) * step)
            if (level == keys.lastIndex) "$pad$key: ${scalar(value)}" else "$pad$key:"
        }.joinToString("\n")
}
