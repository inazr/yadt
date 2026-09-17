package com.dbthelper.core

import com.dbthelper.core.model.*
import com.fasterxml.jackson.databind.JsonNode

/** Turns a parsed `target/manifest.json` into a [ManifestIndex]. Pure: no IDE or file access. */
object ManifestParser {

    fun parse(root: JsonNode): ManifestIndex {
    val nodes = parseNodes(root.get("nodes"))
    val sources = parseSources(root.get("sources"))
    val macros = parseMacros(root.get("macros"))
    val exposures = parseExposures(root.get("exposures"))

    val parentMap = mutableMapOf<String, List<String>>()
    val childMapBuilder = mutableMapOf<String, MutableList<String>>()
    val filePathMap = mutableMapOf<String, String>()
    val relationMap = mutableMapOf<String, String>()
    val patchPathMapBuilder = mutableMapOf<String, MutableList<String>>()

    for ((id, node) in nodes) {
        val path = node.originalFilePath.toUnixPath()
        filePathMap[path] = id
        node.relationName?.let { relationMap[it] = id }

        // Tests are not lineage nodes — they hang off the model/source they
        // validate. Wiring them into the parent/child maps makes every tested
        // node sprout phantom "+ N more" children that the graph filters out of
        // rendering, so the stub shows a wrong count and can never be expanded.
        // Keep them in `nodes` (DocsPayloadBuilder lists a node's tests) but out
        // of the adjacency maps that drive the lineage graph.
        if (node.resourceType == "test" || node.resourceType == "unit_test") continue

        parentMap[id] = node.dependsOnNodes
        for (parentId in node.dependsOnNodes) {
            childMapBuilder.getOrPut(parentId) { mutableListOf() }.add(id)
        }

        // Map the yml that documents this node (its patch_path) -> node id, so
        // opening a schema.yml can focus all the models/seeds/snapshots it covers.
        if (node.resourceType in BUILDABLE_RESOURCE_TYPES) {
            node.patchFilePath?.let { pp ->
                val rel = pp.toUnixPath()
                if (rel.isNotEmpty()) {
                    patchPathMapBuilder.getOrPut(rel) { mutableListOf() }.add(id)
                }
            }
        }
    }

    for ((id, source) in sources) {
        source.relationName?.let { relationMap[it] = id }
        // Add source file paths — multiple sources can share one yml file,
        // so only store first match per path
        val srcPath = source.originalFilePath.toUnixPath()
        filePathMap.putIfAbsent(srcPath, id)
    }

    for ((id, exposure) in exposures) {
        val expPath = exposure.originalFilePath.toUnixPath()
        filePathMap.putIfAbsent(expPath, id)
        parentMap[id] = exposure.dependsOnNodes
        for (parentId in exposure.dependsOnNodes) {
            childMapBuilder.getOrPut(parentId) { mutableListOf() }.add(id)
        }
    }

    return ManifestIndex(
        nodes = nodes,
        sources = sources,
        macros = macros,
        exposures = exposures,
        parentMap = parentMap,
        childMap = childMapBuilder.mapValues { it.value.toList() },
        filePathMap = filePathMap,
        relationMap = relationMap,
        patchPathMap = patchPathMapBuilder.mapValues { it.value.toList() }
    )
    }

    private fun parseNodes(nodesNode: JsonNode?): Map<String, DbtNode> = nodesNode.mapFields { id, node ->
        // Config as a flat map: scalars keep their type, nested values become their JSON text.
        val configMap = node.get("config")?.takeIf { it.isObject }.mapFields { _, v ->
            when {
                v.isTextual -> v.asText()
                v.isBoolean -> v.asBoolean()
                v.isNumber -> v.numberValue()
                v.isNull -> null
                else -> v.toString()
            }
        }.filterValues { it != null }
        DbtNode(
            uniqueId = id,
            name = node.path("name").asText(""),
            resourceType = node.path("resource_type").asText(""),
            packageName = node.path("package_name").asText(""),
            originalFilePath = node.path("original_file_path").asText(""),
            database = node.path("database").asText(null),
            schema = node.path("schema").asText(null),
            alias = node.path("alias").asText(null),
            description = node.path("description").asText(""),
            columns = parseColumns(node.get("columns")),
            dependsOnNodes = node.path("depends_on").path("nodes").map { it.asText() },
            tags = node.path("tags").map { it.asText() },
            rawCode = node.path("raw_code").asText(null) ?: node.path("raw_sql").asText(null),
            compiledCode = node.path("compiled_code").asText(null) ?: node.path("compiled_sql").asText(null),
            config = configMap,
            fqn = node.path("fqn").map { it.asText() },
            patchPath = node.path("patch_path").asText(null)
        )
    }

    private fun parseSources(sourcesNode: JsonNode?): Map<String, DbtSource> = sourcesNode.mapFields { id, node ->
        // Freshness threshold as "<count> <period>", e.g. "12 hour"; null when not configured.
        fun threshold(key: String): String? = node.get("freshness")?.get(key)?.let { fa ->
            val count = fa.path("count").asInt(0)
            val period = fa.path("period").asText(null)
            if (count > 0 && period != null) "$count $period" else null
        }
        DbtSource(
            uniqueId = id,
            name = node.path("name").asText(""),
            sourceName = node.path("source_name").asText(""),
            packageName = node.path("package_name").asText(""),
            originalFilePath = node.path("original_file_path").asText(""),
            database = node.path("database").asText(null),
            schema = node.path("schema").asText(null),
            identifier = node.path("identifier").asText(null),
            description = node.path("description").asText(""),
            columns = parseColumns(node.get("columns")),
            tags = node.path("tags").map { it.asText() },
            loader = node.path("loader").asText(null),
            freshnessWarnAfter = threshold("warn_after"),
            freshnessErrorAfter = threshold("error_after"),
            loadedAtField = node.path("loaded_at_field").asText(null)
        )
    }

    private fun parseMacros(macrosNode: JsonNode?): Map<String, DbtMacro> = macrosNode.mapFields { id, node ->
        DbtMacro(
            uniqueId = id,
            name = node.path("name").asText(""),
            packageName = node.path("package_name").asText(""),
            originalFilePath = node.path("original_file_path").asText(""),
            description = node.path("description").asText(""),
            arguments = node.path("arguments").map { arg ->
                MacroArgument(
                    name = arg.path("name").asText(""),
                    type = arg.path("type").asText(null),
                    description = arg.path("description").asText("")
                )
            }
        )
    }

    private fun parseExposures(exposuresNode: JsonNode?): Map<String, DbtExposure> = exposuresNode.mapFields { id, node ->
        DbtExposure(
            uniqueId = id,
            name = node.path("name").asText(""),
            packageName = node.path("package_name").asText(""),
            originalFilePath = node.path("original_file_path").asText(""),
            description = node.path("description").asText(""),
            dependsOnNodes = node.path("depends_on").path("nodes").map { it.asText() },
            tags = node.path("tags").map { it.asText() }
        )
    }

    private fun parseColumns(columnsNode: JsonNode?): Map<String, DbtColumn> = columnsNode.mapFields { name, node ->
        val tags = node.path("tags").map { it.asText() }
        val hasPkConstraint = node.path("constraints").any { it.path("type").asText() == "primary_key" }
        DbtColumn(
            name = name,
            description = node.path("description").asText(""),
            dataType = node.path("data_type").asText(null),
            tags = tags,
            isPrimaryKey = hasPkConstraint || "pk" in tags
        )
    }

    /** Entries of a JSON object, in document order, mapped by [transform]; empty for a missing node. */
    private inline fun <T> JsonNode?.mapFields(transform: (key: String, node: JsonNode) -> T): Map<String, T> =
        this?.fields()?.asSequence()?.associate { (key, node) -> key to transform(key, node) } ?: emptyMap()
}
