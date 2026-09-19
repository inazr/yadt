package com.dbthelper.charts.editor

object SqlSelectColumns {

    fun of(sql: String): List<String> {
        val text = blankOutNested(sql)
        val select = topLevelKeyword(text, "select") ?: return emptyList()
        val from = topLevelKeyword(text, "from", select) ?: text.length
        val list = sql.substring(select + "select".length, from)
        val masked = text.substring(select + "select".length, from)
        return splitAt(masked, ',').mapNotNull { (start, end) -> columnName(list.substring(start, end).trim()) }.distinct()
    }

    private fun blankOutNested(sql: String): String {
        val out = StringBuilder(sql)
        var i = 0
        var depth = 0
        while (i < sql.length) {
            when {
                sql.startsWith("{{", i) || sql.startsWith("{%", i) || sql.startsWith("{#", i) -> {
                    val close = when (sql[i + 1]) { '{' -> "}}"; '%' -> "%}"; else -> "#}" }
                    val end = sql.indexOf(close, i + 2).let { if (it < 0) sql.length else it + 2 }
                    for (k in i until end) out[k] = ' '
                    i = end
                    continue
                }
                sql[i] == '\'' || sql[i] == '"' -> {
                    val end = sql.indexOf(sql[i], i + 1).let { if (it < 0) sql.length else it + 1 }
                    for (k in i until end) out[k] = ' '
                    i = end
                    continue
                }
                sql[i] == '(' -> { depth++; out[i] = ' ' }
                sql[i] == ')' -> { depth = maxOf(0, depth - 1); out[i] = ' ' }
                depth > 0 -> out[i] = ' '
            }
            i++
        }
        return out.toString()
    }

    private fun topLevelKeyword(masked: String, keyword: String, after: Int = -1): Int? {
        val regex = Regex("""\b$keyword\b""", RegexOption.IGNORE_CASE)
        val hits = regex.findAll(masked).map { it.range.first }.filter { it > after }.toList()
        return if (keyword == "select") hits.firstOrNull() else hits.firstOrNull()
    }

    private fun splitAt(masked: String, separator: Char): List<Pair<Int, Int>> {
        val parts = mutableListOf<Pair<Int, Int>>()
        var start = 0
        masked.forEachIndexed { i, c -> if (c == separator) { parts += start to i; start = i + 1 } }
        parts += start to masked.length
        return parts
    }

    private fun columnName(item: String): String? {
        if (item.isEmpty() || item == "*" || item.endsWith(".*")) return null
        val alias = Regex("""(?is).*\bas\s+([\w"`]+)\s*$""").matchEntire(item)?.groupValues?.get(1)
            ?: Regex("""(?s).*[)\s]([A-Za-z_]\w*)\s*$""").matchEntire(item)?.groupValues?.get(1)?.takeIf { !item.trimEnd().endsWith(")") }
            ?: item.substringAfterLast('.').takeIf { Regex("""[\w"`]+""").matches(it) }
        return alias?.trim('"', '`')
    }
}
