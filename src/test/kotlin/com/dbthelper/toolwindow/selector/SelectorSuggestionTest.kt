package com.dbthelper.toolwindow.selector

import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.DbtSource
import com.dbthelper.core.model.ManifestIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SelectorSuggestionTest {

    private fun node(id: String, name: String, type: String = "model",
                     tags: List<String> = emptyList(),
                     path: String = "models/$name.sql",
                     fqn: List<String> = listOf("proj", name)) =
        DbtNode(uniqueId = id, name = name, resourceType = type, packageName = "proj",
                originalFilePath = path, tags = tags, fqn = fqn)

    private fun index() = ManifestIndex(
        nodes = mapOf(
            "model.proj.stg_orders" to node("model.proj.stg_orders", "stg_orders",
                tags = listOf("daily"), path = "models/staging/stg_orders.sql"),
            "model.proj.stg_customers" to node("model.proj.stg_customers", "stg_customers",
                tags = listOf("daily", "pii"), path = "models/staging/stg_customers.sql"),
            "seed.proj.country_codes" to node("seed.proj.country_codes", "country_codes",
                type = "seed", path = "seeds/country_codes.csv"),
            "test.proj.not_null" to node("test.proj.not_null", "not_null_stg_orders_id",
                type = "test", path = "models/staging/schema.yml")
        ),
        sources = mapOf(
            "source.proj.raw.orders" to DbtSource(
                uniqueId = "source.proj.raw.orders", name = "orders", sourceName = "raw",
                packageName = "proj", originalFilePath = "models/sources.yml",
                tags = listOf("pii"))
        )
    )

    @Test
    fun `candidate pools are built, deduped and sorted from the manifest`() {
        val c = SelectorCandidates.from(index())
        assertEquals(listOf("country_codes", "stg_customers", "stg_orders"), c.models)
        assertEquals(listOf("daily", "pii"), c.tags)
        assertEquals(listOf("raw.orders"), c.sources)
        assertTrue(c.fqns.contains("proj.stg_orders"))
        assertTrue(c.paths.contains("models"))
        assertTrue(c.paths.contains("models/staging"))
        assertTrue(c.paths.contains("seeds"))
        // test-type node fqns must not leak into the fqn pool
        assertTrue(c.fqns.none { it.contains("not_null") })
    }
}
