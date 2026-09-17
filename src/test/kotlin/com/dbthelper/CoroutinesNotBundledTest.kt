package com.dbthelper

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Services take the platform-injected `CoroutineScope` (ManifestService, DctSchemaResolver). The IDE
 * matches light-service constructors by class identity, so a bundled kotlinx-coroutines jar would make
 * that parameter a different class than the platform's: the service then fails with "does not define
 * any of supported signatures" and never exists (seen on PyCharm 2026.2 with YADT 0.6.0). The Plugin
 * Verifier can't catch this, so this test keeps the dependency out of the build.
 */
class CoroutinesNotBundledTest {

    @Test
    fun `the build does not bundle kotlinx-coroutines`() {
        val build = File("build.gradle.kts").readText() + File("gradle/libs.versions.toml").readText()
        assertFalse(build.contains("coroutines"), "Use the IDE's kotlinx-coroutines; never declare it as a dependency")
    }
}
