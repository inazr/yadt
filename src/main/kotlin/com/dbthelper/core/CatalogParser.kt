package com.dbthelper.core

import com.dbthelper.core.model.DbtColumn
import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.DbtSource
import com.dbthelper.core.model.ManifestIndex
import com.fasterxml.jackson.databind.JsonNode
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class CatalogParser(private val project: Project) {

    private val logger = Logger.getInstance(CatalogParser::class.java)

    fun mergeCatalog(index: ManifestIndex): ManifestIndex {
        val locator = DbtProjectLocator.getInstance(project)
        val catalogFile = locator.getCatalogFile() ?: return index

        return try {
            val root = catalogFile.inputStream.use { jsonMapper.readTree(it) }
            val catalogNodes = root.get("nodes") ?: return index
            val catalogSources = root.get("sources")

            val updatedNodes = index.nodes.toMutableMap()
            mergeNodeColumns(catalogNodes, updatedNodes)

            val updatedSources = index.sources.toMutableMap()
            if (catalogSources != null) {
                mergeSourceColumns(catalogSources, updatedSources)
            }

            index.copy(nodes = updatedNodes, sources = updatedSources)
        } catch (e: Exception) {
            logger.warn("Failed to parse catalog.json", e)
            index
        }
    }

    private fun mergeNodeColumns(catalogNodes: JsonNode, nodes: MutableMap<String, DbtNode>) {
        for ((id, catalogNode) in catalogNodes.fields()) {
            val existing = nodes[id] ?: continue
            val columns = mergeColumns(catalogNode, existing.columns) ?: continue
            nodes[id] = existing.copy(columns = columns)
        }
    }

    private fun mergeSourceColumns(catalogSources: JsonNode, sources: MutableMap<String, DbtSource>) {
        for ((id, catalogNode) in catalogSources.fields()) {
            val existing = sources[id] ?: continue
            val columns = mergeColumns(catalogNode, existing.columns) ?: continue
            sources[id] = existing.copy(columns = columns)
        }
    }

    /**
     * [existing] columns with the catalog's warehouse types filled in; catalog-only columns are
     * added. Null when the catalog entry has no `columns`.
     */
    private fun mergeColumns(catalogNode: JsonNode, existing: Map<String, DbtColumn>): Map<String, DbtColumn>? {
        val catalogColumns = catalogNode.path("columns")
        if (catalogColumns.isMissingNode) return null
        val merged = existing.toMutableMap()
        for ((colName, colNode) in catalogColumns.fields()) {
            val known = merged[colName]
            merged[colName] = DbtColumn(
                name = colName,
                description = known?.description ?: "",
                dataType = colNode.path("type").asText(null) ?: known?.dataType,
                tags = known?.tags ?: emptyList(),
                isPrimaryKey = known?.isPrimaryKey ?: false
            )
        }
        return merged
    }
}
