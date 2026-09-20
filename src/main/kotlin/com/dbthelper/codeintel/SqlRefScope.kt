package com.dbthelper.codeintel

/**
 * The tables a piece of SQL can select from: every `ref()` / `source()` in it, reachable under the
 * relation's own name (dbt renders `{{ ref('x') }}` to a relation whose last segment is `x`, which
 * SQL then accepts as an implicit qualifier) and under an explicit alias. Lookup is case-insensitive
 * like SQL identifiers. Pure: no IDE or manifest access — the caller resolves the target.
 */
object SqlRefScope {

    sealed interface Target {
        data class Model(val name: String) : Target
        data class Source(val source: String, val table: String) : Target
    }

    /** Aliases that are really the next SQL clause, not a name for the table. */
    private val KEYWORDS = setOf(
        "as", "on", "using", "where", "group", "order", "having", "limit", "join", "inner", "left",
        "right", "full", "cross", "outer", "union", "qualify", "window", "select", "from", "when",
        "and", "or", "not", "lateral", "unnest", "pivot", "unpivot", "with", "by",
    )

    private val CALL = Regex(
        """\{\{[-\s]*(ref|source)\s*\(\s*['"]([^'"]+)['"]\s*(?:,\s*['"]([^'"]+)['"]\s*)?\)[-\s]*}}""" +
            """(?:\s+(?:[aA][sS]\s+)?([A-Za-z_]\w*))?""",
    )

    class Scope(private val targets: Map<String, Target>) {
        operator fun get(qualifier: String): Target? = targets[qualifier.lowercase()]
    }

    fun of(sql: String): Scope {
        val targets = mutableMapOf<String, Target>()
        for (match in CALL.findAll(sql)) {
            val (kind, first, second, alias) = match.destructured
            val target = if (kind == "source" && second.isNotEmpty()) Target.Source(first, second) else Target.Model(first)
            val relationName = if (target is Target.Source) target.table else first
            targets.putIfAbsent(relationName.lowercase(), target)
            alias.takeIf { it.isNotEmpty() && it.lowercase() !in KEYWORDS }
                ?.let { targets[it.lowercase()] = target }
        }
        return Scope(targets)
    }
}
