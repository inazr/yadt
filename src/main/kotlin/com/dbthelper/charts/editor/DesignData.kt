package com.dbthelper.charts.editor

import com.dbthelper.charts.DbtChartsBoardLocator
import com.dbthelper.charts.DctSchemaListener
import com.dbthelper.charts.DctSchemaResolver
import com.dbthelper.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.Yaml

/** What the Design tab reads from the resolved dct install, cached until the schema changes. */
@Service(Service.Level.PROJECT)
class DesignData(private val project: Project) {

    @Volatile private var schema: Pair<Path, JsonNode>? = null
    @Volatile private var palettes: Pair<Path?, DctPalettes>? = null

    init {
        project.messageBus.connect().subscribe(
            DctSchemaListener.TOPIC,
            object : DctSchemaListener {
                override fun onSchemaChanged(schema: Path?) {
                    this@DesignData.schema = null
                    this@DesignData.palettes = null
                }
            },
        )
    }

    fun schema(): JsonNode? {
        val path = DctSchemaResolver.getInstance(project).schemaPath ?: return null
        schema?.takeIf { it.first == path }?.let { return it.second }
        return runCatching { jsonMapper.readTree(Files.readString(path)) }.getOrNull()?.also { schema = path to it }
    }

    fun palettes(): DctPalettes {
        val dir = DctSchemaResolver.getInstance(project).dctPackageDir
        palettes?.takeIf { it.first == dir }?.let { return it.second }
        return (dir?.let(DctPalettes::load) ?: DctPalettes.EMPTY).also { palettes = dir to it }
    }

    /** `meta.yml`/`meta.yaml` from `charts/` down to the board's directory, outermost first. */
    fun metaDocs(board: VirtualFile): List<Map<String, Any?>> {
        val chartsDir = DbtChartsBoardLocator.chartsDirOf(board) ?: return emptyList()
        val dirs = generateSequence(board.parent) { if (it == chartsDir) null else it.parent }.toList().reversed()
        return dirs.flatMap { dir -> listOf("meta.yml", "meta.yaml").mapNotNull { dir.findChild(it) } }.mapNotNull { file ->
            @Suppress("UNCHECKED_CAST")
            runCatching { Yaml().load<Any?>(String(file.contentsToByteArray(), file.charset)) as? Map<String, Any?> }.getOrNull()
        }
    }

    companion object {
        fun getInstance(project: Project): DesignData = project.service()
    }
}
