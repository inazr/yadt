package com.dbthelper.core.model

data class LineageGraph(
    val currentNodeId: String,
    val nodes: List<LineageNode>,
    val edges: List<LineageEdge>,
    val edgeCurveStyle: String = "bezier",
    val layoutDirection: String = "LR",
    val nodeColorMode: String = "resource",
    val catalogAvailable: Boolean = false
)

data class LineageNode(
    val id: String,
    val name: String,
    val resourceType: String,
    val schema: String? = null,
    val database: String? = null,
    val materialization: String? = null,
    val filePath: String? = null,
    val description: String? = null,
    val columns: List<ColumnNode> = emptyList(),
    val depth: Int,
    val isCurrent: Boolean = false,
    val stubDirection: String? = null,
    val boundaryNodeId: String? = null,
    val searchHints: com.dbthelper.core.SearchHints? = null,
    val parent: String? = null,
    val isParent: Boolean = false,
    val freshness: com.dbthelper.core.SourceFreshness? = null,
)

data class ColumnNode(
    val name: String,
    @com.fasterxml.jackson.annotation.JsonProperty("type")
    val dataType: String?,
    val description: String?,
    val isPrimaryKey: Boolean = false
)

data class LineageEdge(
    val fromNodeId: String,
    val toNodeId: String
)

