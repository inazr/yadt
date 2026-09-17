package com.dbthelper.core

import com.dbthelper.core.model.*
import com.dbthelper.settings.DbtHelperSettings
import com.fasterxml.jackson.databind.JsonNode
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.*

@Service(Service.Level.PROJECT)
class ManifestService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(ManifestService::class.java)
    private val locator get() = DbtProjectLocator.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedIndex: ManifestIndex = ManifestIndex.EMPTY

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var isLoading: Boolean = false
        private set

    fun getIndex(): ManifestIndex = cachedIndex

    fun reparse() {
        scope.launch {
            doParse()
        }
    }

    private fun doParse() {
        isLoading = true
        lastError = null
        try {
            // Honor the "Project root override" setting so a multi-project workspace
            // can't silently load another project's manifest. With no override we keep
            // the original behaviour: scan every discovered root. (findProjectRoot is the
            // same override-aware resolution DbtCommandRunner uses, so the parsed manifest
            // and the runner now agree on which project is active.)
            val overrideConfigured = DbtHelperSettings.getInstance(project).state.dbtProjectRootOverride.isNotBlank()
            val candidateRoots = candidateManifestRoots(
                overrideConfigured = overrideConfigured,
                overrideRoot = if (overrideConfigured) locator.findProjectRoot() else null,
                discoveredRoots = locator.findAllDbtRoots(),
            )
            if (candidateRoots.isEmpty()) {
                cachedIndex = ManifestIndex.EMPTY
                lastError = "No dbt projects found"
                return
            }

            // First root with a built manifest (an override yields a single-element list).
            // TODO: support multi-project manifest merging
            val manifestFile = candidateRoots.firstNotNullOfOrNull { root ->
                root.findChild("target")?.findChild("manifest.json")
            }
            if (manifestFile == null) {
                cachedIndex = ManifestIndex.EMPTY
                lastError = "manifest.json not found"
                return
            }
            val root = manifestFile.inputStream.use { jsonMapper.readTree(it) }

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

            var index = ManifestIndex(
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

            // Merge catalog if available
            val catalogParser = project.service<CatalogParser>()
            index = catalogParser.mergeCatalog(index)

            cachedIndex = index
            logger.info("dbt manifest parsed: ${index.modelCount} models, ${index.sourceCount} sources")

            // Notify listeners via message bus
            project.messageBus.syncPublisher(ManifestUpdateListener.TOPIC).onManifestUpdated(index)
        } catch (e: Exception) {
            lastError = e.message
            logger.warn("Failed to parse manifest", e)
        } finally {
            isLoading = false
        }
    }

    fun findCurrentModelId(file: VirtualFile): String? {
        val relativePath = locator.getRelativePath(file) ?: return null
        return cachedIndex.findByFilePath(relativePath)
    }

    /**
     * Ids of the model/seed/snapshot nodes documented by [file] when it is a schema
     * yml (resolved via each node's patch_path). Empty for non-yml files, ymls that
     * only define sources/tests, or files outside the project.
     */
    fun findDocumentedNodeIds(file: VirtualFile): List<String> {
        if (file.extension?.lowercase() !in setOf("yml", "yaml")) return emptyList()
        val relativePath = locator.getRelativePath(file) ?: return emptyList()
        return cachedIndex.getDocumentedNodes(relativePath)
    }

    /** Resolve a plain dbt model name to its node uniqueId, or null if not found. */
    fun findModelIdByName(modelName: String): String? {
        val target = modelName.trim()
        if (target.isEmpty()) return null
        return getIndex().nodes.values
            .firstOrNull { it.resourceType == "model" && it.name == target }
            ?.uniqueId
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

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): ManifestService =
            project.service<ManifestService>()

        /**
         * Roots to consider for manifest parsing. A configured override pins parsing to
         * that single project; otherwise every discovered root is a candidate. This is
         * the fix's core decision — see the call site in [doParse].
         */
        internal fun <T> candidateManifestRoots(
            overrideConfigured: Boolean,
            overrideRoot: T?,
            discoveredRoots: List<T>,
        ): List<T> = if (overrideConfigured) listOfNotNull(overrideRoot) else discoveredRoots
    }
}
