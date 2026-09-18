package com.dbthelper.charts.preview

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewProviderDeclarationTest {

    private val provider = "com.dbthelper.charts.preview.DbtChartsPreviewEditorProvider"

    @Test
    fun `board preview provider is registered in the main descriptor`() {
        val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(
            Regex("""<fileEditorProvider[^>]*implementation="${Regex.escape(provider)}"""").containsMatchIn(pluginXml),
            "plugin.xml must register $provider as a fileEditorProvider",
        )
    }

    @Test
    fun `board preview provider is not hidden in the 262-only JCEF sub-descriptor`() {
        val jcefXml = File("src/main/resources/META-INF/yadt-jcef.xml").readText()
        assertFalse(jcefXml.contains(provider), "yadt-jcef.xml only loads on 262+; the preview must work from 251")
    }
}
