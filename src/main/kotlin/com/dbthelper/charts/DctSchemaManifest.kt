package com.dbthelper.charts

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class SchemaEntry(val version: String, val file: String, val sha256: String)

/**
 * Reads dbt Charts' schema index (`dbt_charts/data/schemas/yaml/manifest.json`). Entries are
 * `RELEASED` (frozen file + sha256) or `DEV` (no file yet); only released ones are usable.
 */
object DctSchemaManifest {
    private val mapper = ObjectMapper()

    fun newestReleased(manifestJson: String): SchemaEntry? {
        val root = runCatching { mapper.readTree(manifestJson) }.getOrNull() ?: return null
        return root.path("schemas")
            .filter { it.path("status").asText() == "RELEASED" && it.path("file").isTextual && it.path("sha256").isTextual }
            .map { SchemaEntry(it.path("version").asText(), it.path("file").asText(), it.path("sha256").asText()) }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    }

    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun sha256Matches(file: Path, expected: String): Boolean {
        if (!Files.isRegularFile(file)) return false
        val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
        return actual.equals(expected.trim(), ignoreCase = true)
    }

    /** Newest `<version>.json` in the download cache whose `<version>.sha256` sidecar still matches. */
    fun newestCached(dir: Path): Path? {
        if (!Files.isDirectory(dir)) return null
        val schemas = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".json") }.toList() }
        return schemas
            .sortedWith { a, b -> compareVersions(b.fileName.toString().removeSuffix(".json"), a.fileName.toString().removeSuffix(".json")) }
            .firstOrNull { json ->
                val sidecar = json.resolveSibling(json.fileName.toString().removeSuffix(".json") + ".sha256")
                Files.isRegularFile(sidecar) && sha256Matches(json, Files.readString(sidecar))
            }
    }
}
