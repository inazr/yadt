package com.dbthelper.core

import com.dbthelper.core.ManifestService.Companion.candidateManifestRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers the root-selection fix: when a "Project root override" is set, manifest parsing
 * must pin to that one project instead of falling back to scanning every discovered root
 * (which silently loaded the wrong project's manifest in a multi-project workspace).
 */
class ManifestServiceTest {

    @Test
    fun overridePinsToThatRootInsteadOfScanningAll() {
        // "short" sorts first when scanning all, but the override must win.
        val roots = listOf("short", "override-root", "longer-path")
        assertEquals(listOf("override-root"), candidateManifestRoots(true, "override-root", roots))
    }

    @Test
    fun unresolvedOverrideYieldsNoCandidates() {
        // Override set but the path didn't resolve -> no roots (caller reports "No dbt projects found").
        assertEquals(emptyList<String>(), candidateManifestRoots(true, null, listOf("a", "b")))
    }

    @Test
    fun noOverrideKeepsAllDiscoveredRoots() {
        // Original behaviour preserved: every discovered root stays a candidate.
        assertEquals(listOf("a", "b"), candidateManifestRoots(false, null, listOf("a", "b")))
    }
}
