package com.dbthelper.actions

import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.ManifestIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CompiledSqlTest {

    private val index = ManifestIndex(nodes = listOf("a", "b", "c").associate { name ->
        "model.p.$name" to DbtNode("model.p.$name", name, "model", "p", "models/$name.sql")
    })

    private fun compile(dir: File, vararg names: String) = names.forEach { name ->
        File(dir, "models/$name.sql").apply { parentFile.mkdirs() }.writeText("  select '$name'\n")
    }

    @Test
    fun `a single-model selector copies just that model`(@TempDir dir: File) {
        compile(dir, "a", "b")
        assertEquals(CompiledSql.Result("  select 'b'\n", "b"), CompiledSql.collect(index, dir, "+b+"))
    }

    @Test
    fun `any other selector concatenates every compiled model under a header`(@TempDir dir: File) {
        compile(dir, "a", "c")
        assertEquals(
            CompiledSql.Result("-- a\nselect 'a'\n\n-- c\nselect 'c'\n\n", "2 models"),
            CompiledSql.collect(index, dir, "tag:nightly")
        )
    }

    @Test
    fun `nothing compiled yields null`(@TempDir dir: File) {
        assertNull(CompiledSql.collect(index, dir, "b"))
        assertNull(CompiledSql.collect(index, dir, ""))
    }
}
