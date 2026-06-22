package com.dbthelper.actions

import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.ManifestIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReplaceRefsWithRelationsTest {

    private fun index(): ManifestIndex {
        val node = DbtNode(
            uniqueId = "model.proj.int_x", name = "int_x", resourceType = "model",
            packageName = "proj", originalFilePath = "models/int_x.sql",
            database = "schuettflix-bi", schema = "transformation",
        )
        return ManifestIndex(nodes = mapOf(node.uniqueId to node))
    }

    @Test
    fun `ref expands to schema_table when database excluded`() {
        val out = CopyWithRefsReplacedAction.replaceRefsWithRelations(
            "select * from {{ ref('int_x') }}", index(), includeDatabase = false,
        )
        assertEquals("select * from transformation.int_x", out)
    }

    @Test
    fun `ref expands to database_schema_table by default`() {
        val out = CopyWithRefsReplacedAction.replaceRefsWithRelations(
            "select * from {{ ref('int_x') }}", index(),
        )
        assertEquals("select * from schuettflix-bi.transformation.int_x", out)
    }
}
