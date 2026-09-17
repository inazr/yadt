package com.dbthelper.codeintel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** dbt Charts boards use `{{ filter(...) }}` etc., which aren't dbt macros, so boards get no macro completion. */
class DbtCompletionContextTest {

    @Test
    fun `macro context is offered by default`() {
        assertEquals(DbtJinjaUtils.CompletionContext.Macro("fil"), DbtJinjaUtils.detectCompletionContext("WHERE {{ fil"))
    }

    @Test
    fun `macro context is suppressed when macros are not allowed`() {
        assertNull(DbtJinjaUtils.detectCompletionContext("WHERE {{ fil", allowMacros = false))
    }

    @Test
    fun `ref and source contexts still work without macros`() {
        assertEquals(
            DbtJinjaUtils.CompletionContext.Ref("fct_"),
            DbtJinjaUtils.detectCompletionContext("FROM {{ ref('fct_", allowMacros = false),
        )
        assertEquals(
            DbtJinjaUtils.CompletionContext.SourceTable("raw", "ord"),
            DbtJinjaUtils.detectCompletionContext("FROM {{ source('raw', 'ord", allowMacros = false),
        )
    }
}
