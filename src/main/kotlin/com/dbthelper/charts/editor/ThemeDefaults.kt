package com.dbthelper.charts.editor

/**
 * The style a field inherits when unset, deep-merged in dct's own precedence order: a theme's
 * `_base` ancestor first, then the theme itself (via [DctPalettes.themeChain]), then `meta.yml`
 * cascades from `charts/` down to the board — each later doc winning field-by-field. A field's
 * value then comes from `charts.<family>` in that merged style, else `charts`, else the style root.
 */
class ThemeDefaults(palettes: DctPalettes, boardTheme: String?, metaDocs: List<Map<String, Any?>>) {

    val theme: String = boardTheme ?: metaDocs.mapNotNull { it["theme"] as? String }.lastOrNull() ?: DEFAULT_THEME

    private val style: Map<String, Any?> =
        (palettes.themeChain(theme).map(DctPalettes::style) + metaDocs.map(DctPalettes::style))
            .fold(emptyMap()) { acc, next -> deepMerge(acc, next) }

    fun board(path: List<String>): String? = when {
        path == listOf("theme") -> theme
        path.firstOrNull() == "style" -> scalarAt(style, path.drop(1))
        else -> null
    }

    fun chart(family: String, path: List<String>): String? {
        if (path.firstOrNull() != "style") return null
        val rest = path.drop(1)
        return scalarAt(style, listOf("charts", family) + rest)
            ?: scalarAt(style, listOf("charts") + rest)
            ?: scalarAt(style, rest)
    }

    companion object {
        const val DEFAULT_THEME = "clarity"

        private fun scalarAt(tree: Map<String, Any?>, path: List<String>): String? {
            var node: Any? = tree
            for (segment in path) node = (node as? Map<*, *>)?.get(segment) ?: return null
            return node?.takeUnless { it is Map<*, *> || it is List<*> }?.let(::format)
        }

        private fun format(value: Any): String =
            if (value is Double && value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()

        @Suppress("UNCHECKED_CAST")
        private fun deepMerge(base: Map<String, Any?>, over: Map<String, Any?>): Map<String, Any?> =
            (base.keys + over.keys).associateWith { key ->
                val b = base[key]
                val o = over[key]
                if (b is Map<*, *> && o is Map<*, *>) deepMerge(b as Map<String, Any?>, o as Map<String, Any?>)
                else if (over.containsKey(key)) o else b
            }
    }
}
