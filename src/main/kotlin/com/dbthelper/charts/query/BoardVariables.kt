package com.dbthelper.charts.query

import org.yaml.snakeyaml.Yaml

/**
 * The `variables:` a board declares, so a query run can pass a value for each one. dct fails a run
 * whose Jinja reads an undefined variable, so the preview asks for them up front.
 */
object BoardVariables {

    data class Variable(val name: String, val default: String?)

    @Suppress("UNCHECKED_CAST")
    fun of(boardYaml: String): List<Variable> = runCatching {
        val doc = Yaml().load<Any?>(boardYaml) as? Map<String, Any?> ?: return emptyList()
        val variables = doc["variables"] as? Map<String, Any?> ?: return emptyList()
        variables.map { (name, body) ->
            Variable(name, (body as? Map<*, *>)?.get("default")?.toString())
        }
    }.getOrDefault(emptyList())
}
