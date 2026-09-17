package com.dbthelper.core

import com.dbthelper.core.model.*
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class ManifestService(private val project: Project, private val scope: CoroutineScope) {

    private val logger = Logger.getInstance(ManifestService::class.java)
    private val locator get() = DbtProjectLocator.getInstance(project)

    @Volatile
    private var cachedIndex: ManifestIndex = ManifestIndex.EMPTY

    fun getIndex(): ManifestIndex = cachedIndex

    fun reparse() {
        scope.launch(Dispatchers.IO) {
            doParse()
        }
    }

    private fun doParse() {
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
                return
            }

            // First root with a built manifest (an override yields a single-element list).
            // TODO: support multi-project manifest merging
            val manifestFile = candidateRoots.firstNotNullOfOrNull { root ->
                root.findChild("target")?.findChild("manifest.json")
            }
            if (manifestFile == null) {
                cachedIndex = ManifestIndex.EMPTY
                return
            }
            val manifest = manifestFile.inputStream.use { jsonMapper.readTree(it) }
            val index = mergeCatalog(ManifestParser.parse(manifest))

            cachedIndex = index
            logger.info("dbt manifest parsed: ${index.modelCount} models, ${index.sourceCount} sources")

            // Notify listeners via message bus
            project.messageBus.syncPublisher(ManifestUpdateListener.TOPIC).onManifestUpdated(index)
        } catch (e: Exception) {
            logger.warn("Failed to parse manifest", e)
        }
    }

    /** [index] with warehouse column types from catalog.json; unchanged when it is absent or unreadable. */
    private fun mergeCatalog(index: ManifestIndex): ManifestIndex {
        val catalogFile = locator.getCatalogFile() ?: return index
        return try {
            CatalogMerger.merge(index, catalogFile.inputStream.use { jsonMapper.readTree(it) })
        } catch (e: Exception) {
            logger.warn("Failed to parse catalog.json", e)
            index
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
