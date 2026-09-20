package com.dbthelper.charts.editor

import com.dbthelper.charts.DbtChartsBoardLocator
import com.dbthelper.charts.DctSchemaListener
import com.dbthelper.charts.DctSchemaResolver
import com.dbthelper.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.yaml.snakeyaml.Yaml

/**
 * What the Design tab reads from the resolved dct install, cached until the schema changes. The
 * schema and the palettes are loaded on a background coroutine (they are file IO plus a parse);
 * [schema] never blocks and reports [Schema.Loading] until the load is done.
 */
@Service(Service.Level.PROJECT)
class DesignData(private val project: Project, private val cs: CoroutineScope) {

    sealed interface Schema {
        /** The load is running; ask again after [Listener.onDesignDataReady]. */
        data object Loading : Schema

        /** No dct schema, or its file can't be read. */
        data object Missing : Schema
        data class Ready(val node: JsonNode) : Schema
    }

    /** Fired on the EDT once a background load finished (successfully or not). */
    fun interface Listener {
        fun onDesignDataReady()
    }

    private class Loaded(val path: Path, val node: JsonNode?, val palettes: DctPalettes)

    private class Meta(val stamps: List<Pair<String, Long>>, val docs: List<Map<String, Any?>>)

    @Volatile private var loaded: Loaded? = null
    @Volatile private var loading: Path? = null
    private val metaCache = ConcurrentHashMap<String, Meta>()

    init {
        project.messageBus.connect().subscribe(
            DctSchemaListener.TOPIC,
            object : DctSchemaListener {
                override fun onSchemaChanged(schema: Path?) {
                    loaded = null
                    metaCache.clear()
                }
            },
        )
    }

    fun schema(): Schema {
        val path = DctSchemaResolver.getInstance(project).schemaPath ?: return Schema.Missing
        loaded?.takeIf { it.path == path }?.let { return it.node?.let(Schema::Ready) ?: Schema.Missing }
        warm(path)
        return Schema.Loading
    }

    /** The palettes of the loaded schema; empty while the load runs or when dct has none. */
    fun palettes(): DctPalettes = loaded?.palettes ?: DctPalettes.EMPTY

    /** `meta.yml`/`meta.yaml` from `charts/` down to the board's directory, outermost first. */
    fun metaDocs(board: VirtualFile): List<Map<String, Any?>> {
        val files = metaFiles(board)
        val stamps = files.map { it.path to it.modificationStamp }
        metaCache[board.path]?.takeIf { it.stamps == stamps }?.let { return it.docs }
        val docs = files.mapNotNull { file ->
            @Suppress("UNCHECKED_CAST")
            runCatching { Yaml().load<Any?>(String(file.contentsToByteArray(), file.charset)) as? Map<String, Any?> }.getOrNull()
        }
        metaCache[board.path] = Meta(stamps, docs)
        return docs
    }

    private fun metaFiles(board: VirtualFile): List<VirtualFile> {
        val chartsDir = DbtChartsBoardLocator.chartsDirOf(board) ?: return emptyList()
        val dirs = generateSequence(board.parent) { if (it == chartsDir) null else it.parent }.toList().reversed()
        return dirs.flatMap { dir -> listOf("meta.yml", "meta.yaml").mapNotNull { dir.findChild(it) } }
    }

    @Synchronized
    private fun warm(path: Path) {
        if (loading == path) return
        loading = path
        cs.launch(Dispatchers.IO) {
            val node = runCatching { jsonMapper.readTree(Files.readString(path)) }.getOrNull()
            val palettes = DctSchemaResolver.getInstance(project).dctPackageDir?.let(DctPalettes::load) ?: DctPalettes.EMPTY
            loaded = Loaded(path, node, palettes)
            loading = null
            ApplicationManager.getApplication().invokeLater(
                { if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).onDesignDataReady() },
                ModalityState.any(),
            )
        }
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("YADT dbt Charts design data", Listener::class.java)

        fun getInstance(project: Project): DesignData = project.service()
    }
}
