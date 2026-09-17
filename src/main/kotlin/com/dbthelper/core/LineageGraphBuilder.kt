package com.dbthelper.core

import com.dbthelper.core.model.*
import java.util.LinkedList

class LineageGraphBuilder(
    private val index: ManifestIndex,
    private val project: com.intellij.openapi.project.Project? = null,
    private val catalogAvailable: Boolean = false,
    private val freshnessByUniqueId: Map<String, com.dbthelper.core.SourceFreshness> = emptyMap()
) {

    fun build(
        currentNodeId: String,
        upstreamDepth: Int = 5,
        downstreamDepth: Int = 5,
        showExposures: Boolean = true,
        expandedBoundaryNodes: Set<String> = emptySet()
    ): LineageGraph {
        val visitedNodes = mutableMapOf<String, Int>() // id -> depth
        val edges = mutableListOf<LineageEdge>()

        // BFS upstream (negative depth), then downstream (positive depth)
        val boundaries = mapOf(
            Direction.UPSTREAM to bfs(currentNodeId, upstreamDepth, Direction.UPSTREAM, visitedNodes, edges),
            Direction.DOWNSTREAM to bfs(currentNodeId, downstreamDepth, Direction.DOWNSTREAM, visitedNodes, edges),
        )

        // Expand specific boundary nodes with extra depth
        for (boundaryId in expandedBoundaryNodes) {
            for ((direction, boundaryIds) in boundaries) {
                if (boundaryId in boundaryIds) bfs(boundaryId, EXPAND_STEP, direction, visitedNodes, edges)
            }
        }

        // Ensure current node is included
        visitedNodes[currentNodeId] = 0

        // Tests are never shown as separate cards
        var lineageNodes = visitedNodes
            .mapNotNull { (id, depth) -> toLineageNode(id, depth, id == currentNodeId) }
            .filter { it.resourceType != "test" && (showExposures || it.resourceType != "exposure") }
            .toMutableList()

        // Cluster nodes into compound parent groups based on the active cluster mode
        val clusterMode = project?.let {
            com.dbthelper.settings.DbtHelperSettings.getInstance(it).state.defaultClusterMode
        } ?: "none"

        if (clusterMode != "none") {
            val parentNodesById = mutableMapOf<String, LineageNode>()
            val withParents = lineageNodes.map { n ->
                val pid = parentIdFor(n, clusterMode) ?: return@map n
                parentNodesById.getOrPut(pid) {
                    LineageNode(id = pid, name = parentLabelFor(pid), resourceType = "cluster", depth = 0, isParent = true)
                }
                n.copy(parent = pid)
            }
            lineageNodes = (parentNodesById.values + withParents).toMutableList()
        }

        // Recalculate stub counts — exclude nodes already visible after expands
        val visibleNodeIds = visitedNodes.keys
        val stubEdges = mutableListOf<LineageEdge>()
        for ((direction, boundaryIds) in boundaries) {
            val stubDepth = if (direction == Direction.UPSTREAM) -(upstreamDepth + 1) else downstreamDepth + 1
            for (boundaryId in boundaryIds) {
                if (boundaryId in expandedBoundaryNodes) continue
                val hidden = getNeighbors(boundaryId, direction).count { it !in visibleNodeIds }
                if (hidden > 0) addBoundaryStub(lineageNodes, stubEdges, boundaryId, direction, hidden, stubDepth)
            }
        }

        // Filter edges — only keep edges where both endpoints are in the graph
        val nodeIds = lineageNodes.map { it.id }.toSet()
        val validEdges = edges.distinct().filter { it.fromNodeId in nodeIds && it.toNodeId in nodeIds } + stubEdges

        // --- Long-jump stub insertion ---
        val skipThreshold = project?.let {
            com.dbthelper.settings.DbtHelperSettings.getInstance(it).state.maxLayerSkipBeforeStub
        } ?: 3

        val realEdges = validEdges.filter { e ->
            !e.fromNodeId.startsWith("__stub_") && !e.toNodeId.startsWith("__stub_")
        }
        val layerMap = computeLayers(
            realEdges.flatMap { listOf(it.fromNodeId, it.toNodeId) }.toSet(),
            realEdges
        )

        val skipEdges = realEdges.filter { e ->
            val fl = layerMap[e.fromNodeId] ?: return@filter false
            val tl = layerMap[e.toNodeId] ?: return@filter false
            (tl - fl) > skipThreshold
        }

        if (skipEdges.isEmpty()) {
            return LineageGraph(
                currentNodeId = currentNodeId,
                nodes = lineageNodes,
                edges = validEdges,
                catalogAvailable = catalogAvailable
            )
        }

        val keptEdges = validEdges - skipEdges.toSet()
        val newStubNodes = mutableListOf<LineageNode>()
        val newStubEdges = mutableListOf<LineageEdge>()
        for (e in skipEdges) {
            val gap = (layerMap[e.toNodeId]!! - layerMap[e.fromNodeId]!! - 1)
            val stubId = "__stub_skip_${e.fromNodeId}__to__${e.toNodeId}"
            newStubNodes.add(stubNode(stubId, "$gap hidden hops", depth = 0, direction = "skip", boundaryId = e.fromNodeId))
            newStubEdges.add(LineageEdge(fromNodeId = e.fromNodeId, toNodeId = stubId))
            newStubEdges.add(LineageEdge(fromNodeId = stubId, toNodeId = e.toNodeId))
        }

        return LineageGraph(
            currentNodeId = currentNodeId,
            nodes = lineageNodes + newStubNodes,
            edges = keptEdges + newStubEdges,
            catalogAvailable = catalogAvailable
        )
    }

    /**
     * Render a faithful view of an already-resolved selection set: every id in
     * [selectedIds] becomes a full card marked isCurrent, edges between selected
     * nodes are drawn, and every connection to a node OUTSIDE the set collapses
     * into the same "+ N more" boundary stub the normal view uses. No display-depth
     * padding — the full cards are exactly the selection (graph operators were
     * already applied during resolution).
     *
     * [expandedBoundaryNodes] holds boundary ids the user clicked to expand; each
     * one's immediate hidden neighbors (both directions) are pulled into view, and
     * stubs are recomputed against the enlarged visible set.
     */
    fun buildForSelection(
        selectedIds: Set<String>,
        expandedBoundaryNodes: Set<String> = emptySet()
    ): LineageGraph {
        fun exists(id: String) =
            index.nodes.containsKey(id) || index.sources.containsKey(id) || index.exposures.containsKey(id)

        val matched = selectedIds.filter { exists(it) }.toMutableSet()
        if (matched.isEmpty()) {
            return LineageGraph(currentNodeId = "", nodes = emptyList(), edges = emptyList(),
                catalogAvailable = catalogAvailable)
        }

        // Reveal hidden neighbors of expanded boundaries (1 hop each click; clicks accumulate).
        val visible = LinkedHashSet(matched)
        for (b in expandedBoundaryNodes) {
            if (!exists(b)) continue
            (index.getUpstream(b) + index.getDownstream(b)).filter { exists(it) }.forEach { visible += it }
        }

        val nodes = visible.mapNotNull { toLineageNode(it, depth = 0, isCurrent = it in matched) }
            .filter { it.resourceType != "test" }
            .toMutableList()
        val visibleIds = nodes.map { it.id }.toSet()

        val edges = mutableListOf<LineageEdge>()
        for (id in visibleIds) {
            for (child in index.getDownstream(id)) {
                if (child in visibleIds) edges.add(LineageEdge(fromNodeId = id, toNodeId = child))
            }
        }

        // Selection cards all sit at depth 0, so boundary stubs use a fixed +/-1
        // (the BFS-depth convention in build() doesn't apply to a flat selection set).
        for (id in visibleIds) {
            for (direction in Direction.entries) {
                val hidden = getNeighbors(id, direction).count { it !in visibleIds }
                val stubDepth = if (direction == Direction.UPSTREAM) -1 else 1
                if (hidden > 0) addBoundaryStub(nodes, edges, id, direction, hidden, stubDepth)
            }
        }

        return LineageGraph(
            currentNodeId = matched.first(),
            nodes = nodes,
            edges = edges,
            catalogAvailable = catalogAvailable
        )
    }

    private fun bfs(
        startId: String,
        maxDepth: Int,
        direction: Direction,
        visitedNodes: MutableMap<String, Int>,
        edges: MutableList<LineageEdge>
    ): Set<String> {
        val queue = LinkedList<Pair<String, Int>>() // (nodeId, currentDepth)
        val visited = mutableSetOf(startId)
        val boundaryIds = LinkedHashSet<String>() // at maxDepth with hidden neighbors beyond

        // Seed with immediate neighbors
        val neighbors = getNeighbors(startId, direction)
        for (neighborId in neighbors) {
            queue.add(neighborId to 1)
            addEdge(edges, startId, neighborId, direction)
        }

        while (queue.isNotEmpty()) {
            val (nodeId, depth) = queue.poll()

            if (nodeId in visited) continue
            visited.add(nodeId)

            if (depth > maxDepth) continue

            val signedDepth = if (direction == Direction.UPSTREAM) -depth else depth
            // Keep the depth closest to current node
            val existing = visitedNodes[nodeId]
            if (existing == null || kotlin.math.abs(signedDepth) < kotlin.math.abs(existing)) {
                visitedNodes[nodeId] = signedDepth
            }

            val nextNeighbors = getNeighbors(nodeId, direction)
            if (depth == maxDepth && nextNeighbors.any { it !in visited }) boundaryIds += nodeId
            for (nextId in nextNeighbors) {
                addEdge(edges, nodeId, nextId, direction)
                if (nextId !in visited) {
                    queue.add(nextId to depth + 1)
                }
            }
        }

        return boundaryIds
    }

    private fun getNeighbors(nodeId: String, direction: Direction): List<String> {
        return when (direction) {
            Direction.UPSTREAM -> index.getUpstream(nodeId)
            Direction.DOWNSTREAM -> index.getDownstream(nodeId)
        }
    }

    private fun addEdge(
        edges: MutableList<LineageEdge>,
        fromId: String,
        toId: String,
        direction: Direction
    ) {
        when (direction) {
            Direction.UPSTREAM -> edges.add(LineageEdge(fromNodeId = toId, toNodeId = fromId))
            Direction.DOWNSTREAM -> edges.add(LineageEdge(fromNodeId = fromId, toNodeId = toId))
        }
    }

    private fun toLineageNode(id: String, depth: Int, isCurrent: Boolean): LineageNode? {
        index.nodes[id]?.let { node ->
            return LineageNode(
                id = id,
                name = node.name,
                resourceType = node.resourceType,
                schema = node.schema,
                database = node.database,
                materialization = node.config["materialized"] as? String,
                filePath = node.originalFilePath,
                description = node.description.ifEmpty { null },
                columns = columnNodes(node.columns),
                depth = depth,
                isCurrent = isCurrent
            ).withSearchHints(node.packageName, node.tags)
        }
        index.sources[id]?.let { source ->
            return LineageNode(
                id = id,
                name = "${source.sourceName}.${source.name}",
                resourceType = "source",
                schema = source.schema,
                database = source.database,
                filePath = source.originalFilePath,
                description = source.description.ifEmpty { null },
                columns = columnNodes(source.columns),
                depth = depth,
                isCurrent = isCurrent,
                freshness = freshnessByUniqueId[id]
            ).withSearchHints(source.packageName, source.tags)
        }
        index.exposures[id]?.let { exposure ->
            return LineageNode(
                id = id,
                name = exposure.name,
                resourceType = "exposure",
                filePath = exposure.originalFilePath,
                description = exposure.description.ifEmpty { null },
                depth = depth,
                isCurrent = isCurrent
            ).withSearchHints(exposure.packageName, exposure.tags)
        }
        return null
    }

    private fun columnNodes(columns: Map<String, DbtColumn>): List<ColumnNode> =
        columns.values.map { ColumnNode(it.name, it.dataType, it.description.ifEmpty { null }, it.isPrimaryKey) }

    private fun LineageNode.withSearchHints(packageName: String, tags: List<String>): LineageNode = copy(
        searchHints = SearchIndexBuilder.buildHints(
            uniqueId = id,
            name = name,
            schema = schema,
            materialization = materialization,
            resourceType = resourceType,
            packageName = packageName,
            columnNames = columns.map { it.name },
            tags = tags
        )
    )

    /** A "+ N more" card for [hidden] neighbors of [boundaryId] outside the graph, wired to that boundary. */
    private fun addBoundaryStub(
        nodes: MutableList<LineageNode>,
        edges: MutableList<LineageEdge>,
        boundaryId: String,
        direction: Direction,
        hidden: Int,
        depth: Int
    ) {
        val stubId = "__stub_${direction.wire}_$boundaryId"
        nodes += stubNode(stubId, "+ $hidden more", depth, direction.wire, boundaryId)
        addEdge(edges, boundaryId, stubId, direction)
    }

    private fun stubNode(id: String, name: String, depth: Int, direction: String, boundaryId: String) =
        LineageNode(id = id, name = name, resourceType = "stub", depth = depth, stubDirection = direction, boundaryNodeId = boundaryId)

    /**
     * Compute a layer index per node: root upstream nodes get 0; for others,
     * layer = max(layer of upstream-neighbors) + 1.
     */
    private fun computeLayers(
        nodeIds: Set<String>,
        edges: List<LineageEdge>
    ): Map<String, Int> {
        val parents = nodeIds.associateWith { mutableListOf<String>() }
        edges.forEach { e ->
            if (e.fromNodeId in nodeIds && e.toNodeId in nodeIds) {
                parents.getValue(e.toNodeId).add(e.fromNodeId)
            }
        }
        val layer = mutableMapOf<String, Int>()
        val resolving = mutableSetOf<String>()
        fun resolve(id: String): Int {
            layer[id]?.let { return it }
            if (id in resolving) return 0 // cycle guard
            resolving.add(id)
            val ps = parents[id].orEmpty()
            val v = if (ps.isEmpty()) 0 else (ps.maxOf { resolve(it) } + 1)
            resolving.remove(id)
            layer[id] = v
            return v
        }
        nodeIds.forEach { resolve(it) }
        return layer
    }

    private fun parentIdFor(node: LineageNode, mode: String): String? = when (mode) {
        "schema" -> if (node.resourceType == "source") {
            val srcGroup = node.name.substringBefore('.', missingDelimiterValue = "_")
            "cluster_source_$srcGroup"
        } else {
            "cluster_schema_${node.schema ?: "_"}"
        }
        "folder" -> {
            val path = node.filePath?.toUnixPath()?.removePrefix("models/")
            val seg = path?.substringBefore('/', missingDelimiterValue = "")
            if (seg.isNullOrBlank()) null else "cluster_folder_$seg"
        }
        "tag" -> {
            // Check manifest index for tags, fall back to searchHints
            val tags = index.nodes[node.id]?.tags?.map { it.lowercase() }
                ?: index.sources[node.id]?.tags?.map { it.lowercase() }
                ?: node.searchHints?.tagsLower?.toList().orEmpty()
            when {
                tags.isEmpty() -> "cluster_tag_<no-tag>"
                tags.size > 1 -> "cluster_tag_<multiple-tags>"
                else -> "cluster_tag_${tags[0]}"
            }
        }
        else -> null
    }

    private fun parentLabelFor(parentId: String): String = when {
        parentId.startsWith("cluster_schema_") -> parentId.removePrefix("cluster_schema_")
        parentId.startsWith("cluster_source_") -> "source: " + parentId.removePrefix("cluster_source_")
        parentId.startsWith("cluster_folder_") -> parentId.removePrefix("cluster_folder_")
        parentId.startsWith("cluster_tag_")    -> parentId.removePrefix("cluster_tag_")
        else -> ""
    }

    private enum class Direction(val wire: String) {
        UPSTREAM("upstream"), DOWNSTREAM("downstream")
    }

    private companion object {
        /** Extra levels revealed each time the user expands a boundary stub. */
        const val EXPAND_STEP = 5
    }
}
