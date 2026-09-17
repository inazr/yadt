package com.dbthelper.charts

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DctSchemaManifestTest {

    @TempDir
    lateinit var tmp: Path

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val manifest = """
        {"schemas": [
          {"version": "0.9.0",  "status": "RELEASED", "file": "0.9.0.json",  "sha256": "aa"},
          {"version": "0.10.0", "status": "RELEASED", "file": "0.10.0.json", "sha256": "bb"},
          {"version": "0.3.1",  "status": "RELEASED", "file": "0.3.1.json",  "sha256": "cc"},
          {"version": "0.11.0", "status": "DEV",      "file": null,          "sha256": null}
        ]}
    """.trimIndent()

    @Test
    fun `picks the newest released entry by numeric version, skipping DEV`() {
        assertEquals(SchemaEntry("0.10.0", "0.10.0.json", "bb"), DctSchemaManifest.newestReleased(manifest))
    }

    @Test
    fun `no released entry or invalid json yields null`() {
        assertNull(DctSchemaManifest.newestReleased("""{"schemas": [{"version": "1.0.0", "status": "DEV", "file": null, "sha256": null}]}"""))
        assertNull(DctSchemaManifest.newestReleased("not json"))
    }

    @Test
    fun `versions compare numerically`() {
        assertTrue(DctSchemaManifest.compareVersions("0.10.0", "0.9.0") > 0)
        assertTrue(DctSchemaManifest.compareVersions("0.9", "0.9.1") < 0)
        assertEquals(0, DctSchemaManifest.compareVersions("1.2.0", "1.2"))
    }

    @Test
    fun `sha256 accepts a matching file and rejects a mismatch or missing file`() {
        val f = tmp.resolve("s.json").also { Files.writeString(it, "{}") }
        assertTrue(DctSchemaManifest.sha256Matches(f, sha("{}").uppercase()))
        assertFalse(DctSchemaManifest.sha256Matches(f, sha("other")))
        assertFalse(DctSchemaManifest.sha256Matches(tmp.resolve("missing.json"), sha("{}")))
    }

    @Test
    fun `newest cached skips files whose sidecar does not match`() {
        Files.writeString(tmp.resolve("0.9.0.json"), "nine")
        Files.writeString(tmp.resolve("0.9.0.sha256"), sha("nine"))
        Files.writeString(tmp.resolve("0.10.0.json"), "ten-corrupted")
        Files.writeString(tmp.resolve("0.10.0.sha256"), sha("ten"))
        assertEquals(tmp.resolve("0.9.0.json"), DctSchemaManifest.newestCached(tmp))
        assertNull(DctSchemaManifest.newestCached(tmp.resolve("nope")))
    }
}
