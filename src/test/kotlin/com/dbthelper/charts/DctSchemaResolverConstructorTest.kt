package com.dbthelper.charts

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * YADT bundles its own kotlinx-coroutines, so a `kotlinx.coroutines.CoroutineScope` in a service
 * constructor is a different class from the platform's. The IDE matches light-service constructors by
 * class identity, fails with "does not define any of supported signatures", and the service never
 * exists (seen on PyCharm 2026.2). The Plugin Verifier can't catch this; this test pins the contract.
 */
class DctSchemaResolverConstructorTest {

    @Test
    fun `resolver is constructible from the project alone`() {
        val signatures = DctSchemaResolver::class.java.constructors.map { c -> c.parameterTypes.map { it.name } }
        assertTrue(
            listOf("com.intellij.openapi.project.Project") in signatures,
            "DctSchemaResolver needs a (Project) constructor; found $signatures",
        )
        assertTrue(
            signatures.none { params -> params.any { it.startsWith("kotlinx.coroutines.") } },
            "Don't inject CoroutineScope: the bundled coroutines jar makes it a different class than the platform's",
        )
    }
}
