package com.dbthelper.charts.editor

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DctFieldOrderTest {

    private val reference = """
        # dbt charts YAML Schema Reference

        <a id="board"></a>
        ## Board
        AuthoredBoard definition from YAML.

        | Field | Type | Description |
        |-------|------|-------------|
        | `title` | str | Heading. |
        | `_schema_version` | str | Written by dct migrate. |
        | `theme` | enum: "clarity" \| "neon" | Built-in theme. |

        ## LineChart

        **Required**

        | Field | Type | Description |
        |-------|------|-------------|
        | `type` | const: "line" |  |

        **Optional**

        | Field | Type | Description |
        |-------|------|-------------|
        | `title` | str | Heading. |
        | `x` | str | X column. |
    """.trimIndent()

    private val order = DctFieldOrder.parse(reference)

    @Test
    fun `sorts fields in dct's declared order`() {
        assertEquals(listOf("title", "_schema_version", "theme"), order.sort("Board", listOf("_schema_version", "theme", "title")))
        assertEquals(listOf("type", "title", "x"), order.sort("LineChart", listOf("title", "type", "x")))
    }

    @Test
    fun `a Patch definition uses its base definition's order`() {
        assertEquals(listOf("title", "x"), order.sort("LineChartPatch", listOf("x", "title")))
    }

    @Test
    fun `fields dct doesn't list follow the known ones in their given order`() {
        assertEquals(listOf("title", "zeta", "alpha"), order.sort("Board", listOf("zeta", "title", "alpha")))
    }

    @Test
    fun `unknown definitions and a missing reference keep the given order`() {
        assertEquals(listOf("b", "a"), order.sort("Unknown", listOf("b", "a")))
        assertEquals(listOf("b", "a"), order.sort(null, listOf("b", "a")))
    }

    @Test
    fun `loading from a package without the reference gives no order`(@TempDir dir: Path) {
        assertEquals(listOf("b", "a"), DctFieldOrder.load(dir).sort("Board", listOf("b", "a")))
    }
}
