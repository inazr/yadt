package com.dbthelper.charts.editor

import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.Yaml
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class DctPalettes(val palettes: Map<String, Palette>, val themes: Map<String, Map<String, Any?>>) {

    data class Palette(val name: String, val family: String, val colors: List<String>, val aliases: Map<String, String>)

    fun themeChain(theme: String): List<Map<String, Any?>> {
        val chain = ArrayDeque<Map<String, Any?>>()
        var name: String? = theme
        val seen = mutableSetOf<String>()
        while (name != null && seen.add(name)) {
            val doc = themes[name] ?: break
            chain.addFirst(doc)
            name = doc["extends"] as? String
        }
        return chain
    }

    fun paletteRoles(theme: String): Map<String, String> =
        themeChain(theme).fold(emptyMap()) { acc, doc -> acc + stringMap(style(doc)["palettes"]) }

    private fun styleRoles(theme: String): Map<String, String> =
        themeChain(theme).fold(emptyMap()) { acc, doc -> acc + stringMap(style(doc)["roles"]) }

    fun resolve(token: String, theme: String): String? {
        val t = token.trim()
        if (HEX.matches(t)) return t
        styleRoles(theme)[t]?.let { return resolve(it, theme) }
        INDEXED.matchEntire(t)?.let { m ->
            return colorAt(palette(m.groupValues[1], theme), m.groupValues[2].toInt())
        }
        val dot = t.lastIndexOf('.')
        if (dot <= 0) return null
        val p = palette(t.substring(0, dot), theme) ?: return null
        val key = t.substring(dot + 1)
        key.toIntOrNull()?.let { return colorAt(p, it) }
        val alias = p.aliases[key] ?: return null
        return alias.toIntOrNull()?.let { colorAt(p, it) } ?: alias.takeIf { HEX.matches(it) }
    }

    private fun palette(nameOrRole: String, theme: String): Palette? =
        palettes[nameOrRole] ?: paletteRoles(theme)[nameOrRole]?.let { palettes[it] }

    private fun colorAt(p: Palette?, oneBased: Int): String? = p?.colors?.getOrNull(oneBased - 1)

    companion object {
        val EMPTY = DctPalettes(emptyMap(), emptyMap())
        private val HEX = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
        private val INDEXED = Regex("""([\w-]+)\[(\d+)]""")

        fun load(packageDir: Path): DctPalettes = runCatching {
            val defaults = packageDir.resolve("core/defaults")
            val palettes = Files.walk(defaults.resolve("palettes")).use { paths ->
                paths.filter { it.extension == "yml" }.toList()
            }.mapNotNull { file -> readPalette(file) }.associateBy { it.name }
            val themes = Files.list(defaults.resolve("themes")).use { paths ->
                paths.filter { it.extension == "yaml" && !it.isDirectory() }.toList()
            }.associate { it.name.removeSuffix(".yaml") to readMap(it) }
            DctPalettes(palettes, themes)
        }.getOrDefault(EMPTY)

        private fun readPalette(file: Path): Palette? {
            val doc = readMap(file)
            val name = doc["name"] as? String ?: return null
            val colors = (doc["colors"] as? List<*>).orEmpty().map { it.toString() }
            val aliases = (doc["aliases"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to v.toString() }
            return Palette(name, file.parent.name, colors, aliases)
        }

        @Suppress("UNCHECKED_CAST")
        private fun readMap(file: Path): Map<String, Any?> =
            (Files.newBufferedReader(file).use { Yaml().load<Any?>(it) } as? Map<String, Any?>).orEmpty()

        @Suppress("UNCHECKED_CAST")
        internal fun style(doc: Map<String, Any?>): Map<String, Any?> = (doc["style"] as? Map<String, Any?>).orEmpty()

        private fun stringMap(value: Any?): Map<String, String> =
            (value as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to v.toString() }
    }
}
