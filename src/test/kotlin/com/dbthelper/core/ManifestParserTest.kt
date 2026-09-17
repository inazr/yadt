package com.dbthelper.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestParserTest {

    private val index = ManifestParser.parse(
        jsonMapper.readTree(
            """
            {
              "nodes": {
                "model.p.orders": {
                  "name": "orders", "resource_type": "model", "package_name": "p",
                  "original_file_path": "models\\orders.sql", "database": "DB", "schema": "Analytics",
                  "depends_on": { "nodes": ["source.p.raw.orders"] },
                  "patch_path": "p://models/schema.yml",
                  "config": { "materialized": "table", "enabled": true, "meta": {}, "alias": null }
                },
                "test.p.not_null_orders_id": {
                  "name": "not_null_orders_id", "resource_type": "test", "package_name": "p",
                  "original_file_path": "models/schema.yml",
                  "depends_on": { "nodes": ["model.p.orders"] }
                }
              },
              "sources": {
                "source.p.raw.orders": {
                  "name": "orders", "source_name": "raw", "package_name": "p",
                  "original_file_path": "models/sources.yml", "database": "DB", "schema": "raw",
                  "freshness": { "warn_after": { "count": 12, "period": "hour" }, "error_after": { "count": 0, "period": null } }
                },
                "source.p.raw.customers": {
                  "name": "customers", "source_name": "raw", "package_name": "p",
                  "original_file_path": "models/sources.yml"
                }
              }
            }
            """.trimIndent()
        )
    )

    @Test
    fun `tests stay in nodes but never enter the lineage adjacency`() {
        assertTrue("test.p.not_null_orders_id" in index.nodes)
        assertEquals(listOf("model.p.orders"), index.getDownstream("source.p.raw.orders"))
        assertEquals(emptyList<String>(), index.getDownstream("model.p.orders"))
    }

    @Test
    fun `paths are normalised and a shared source yml maps to its first source`() {
        assertEquals("model.p.orders", index.findByFilePath("models/orders.sql"))
        assertEquals("source.p.raw.orders", index.findByFilePath("models/sources.yml"))
        assertEquals(listOf("model.p.orders"), index.getDocumentedNodes("models/schema.yml"))
    }

    @Test
    fun `relations, config and freshness thresholds are parsed`() {
        assertEquals("model.p.orders", index.findByRelation("db", "analytics", "orders"))
        assertEquals(mapOf("materialized" to "table", "enabled" to true, "meta" to "{}"), index.nodes.getValue("model.p.orders").config)
        val source = index.sources.getValue("source.p.raw.orders")
        assertEquals("12 hour", source.freshnessWarnAfter)
        assertEquals(null, source.freshnessErrorAfter)
    }
}
