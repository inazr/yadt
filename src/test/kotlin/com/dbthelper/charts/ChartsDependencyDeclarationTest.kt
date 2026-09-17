package com.dbthelper.charts

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The JSON Schema and YAML plugins are bundled in IDEA/PyCharm/DataSpell but not guaranteed in every
 * IntelliJ-Platform IDE. A required <depends> on a missing plugin stops YADT from loading at all, and
 * the Plugin Verifier doesn't catch descriptor wiring, so this test guards it (see JcefDependencyDeclarationTest).
 */
class ChartsDependencyDeclarationTest {

    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    private fun assertOptionalDependency(pluginId: String) {
        val declaration = Regex("""<depends[^>]*>${Regex.escape(pluginId)}</depends>""").find(pluginXml)?.value
        assertTrue(declaration != null, "plugin.xml must declare <depends> on $pluginId")
        assertTrue(declaration!!.contains("optional=\"true\""), "$pluginId must stay optional so YADT loads without it")
        val configFile = Regex("""config-file="([^"]+)"""").find(declaration)?.groupValues?.get(1)
        assertTrue(
            configFile != null && File("src/main/resources/META-INF/$configFile").isFile,
            "optional <depends> on $pluginId needs a config-file that exists",
        )
    }

    @Test
    fun `json schema dependency is optional with an existing config file`() {
        assertOptionalDependency("com.intellij.modules.json")
    }

    @Test
    fun `yaml dependency is optional with an existing config file`() {
        assertOptionalDependency("org.jetbrains.plugins.yaml")
    }
}
