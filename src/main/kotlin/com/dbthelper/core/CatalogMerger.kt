package com.dbthelper.core

import com.dbthelper.core.model.DbtColumn
import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.DbtSource
import com.dbthelper.core.model.ManifestIndex
import com.fasterxml.jackson.databind.JsonNode

/** Fills warehouse column types from a parsed `target/catalog.json` into a [ManifestIndex]. */
object CatalogMerger {

    fun merge(index: ManifestIndex, catalog: JsonNode): ManifestIndex {
        val catalogNodes = catalog.get("nodes") ?: return index
        val updatedNodes = index.nodes.toMutableMap()
        mergeNodeColumns(catalogNodes, updatedNodes)
        val updatedSources = index.sources.toMutableMap()
        catalog.get("sources")?.let { mergeSourceColumns(it, updatedSources) }
        return index.copy(nodes = updatedNodes, sources = updatedSources)
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
