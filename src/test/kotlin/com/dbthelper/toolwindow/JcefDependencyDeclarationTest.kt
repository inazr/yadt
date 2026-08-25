package com.dbthelper.toolwindow

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * LineageTab/DocsTab embed a JCEF browser. Build 262 (2026.2) moved `com.intellij.ui.jcef.*`
 * out of the platform core into the bundled "Web Browser (JCEF)" plugin, so without an explicit
 * dependency the tool window dies with NoClassDefFoundError. Guards the descriptor wiring, which
 * nothing else in the build catches — the IntelliJ Plugin Verifier reports the plugin as
 * "Compatible" either way, because it resolves against the whole IDE classpath rather than the
 * per-plugin classloader graph.
 */
class JcefDependencyDeclarationTest {

    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `plugin declares the JCEF dependency so 2026_2 can resolve com intellij ui jcef`() {
        assertTrue(
            Regex("""<depends[^>]*>com\.intellij\.modules\.jcef</depends>""").containsMatchIn(pluginXml),
            "plugin.xml must depend on com.intellij.modules.jcef — JCEF is a separate plugin since build 262",
        )
    }

    @Test
    fun `the JCEF dependency stays optional so the plugin still loads before build 262`() {
        val declaration = Regex("""<depends[^>]*>com\.intellij\.modules\.jcef</depends>""")
            .find(pluginXml)?.value.orEmpty()
        assertTrue(
            declaration.contains("optional=\"true\""),
            "com.intellij.modules.jcef does not exist before build 262; a required <depends> on it " +
                "makes those IDEs refuse to load the plugin entirely (sinceBuild is 251)",
        )
    }

    @Test
    fun `the optional dependency points at an existing config-file`() {
        val configFile = Regex("""<depends[^>]*config-file="([^"]+)"[^>]*>com\.intellij\.modules\.jcef</depends>""")
            .find(pluginXml)?.groupValues?.get(1)
        assertTrue(
            configFile != null && File("src/main/resources/META-INF/$configFile").isFile,
            "optional <depends> needs a config-file that exists, else the Plugin Verifier reports a structure warning",
        )
    }
}
