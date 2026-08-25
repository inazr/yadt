package com.dbthelper.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DbtCommandBuilderTest {

    private fun spec(verb: DbtVerb, selector: String) = DbtCommandSpec(
        verb = verb,
        selector = selector,
        target = "prod",
        previewLimit = 5,
    )

    @Test
    fun `a blank selector produces a whole-project command without --select`() {
        assertEquals("dbt build --target prod", DbtCommandBuilder.buildDisplay(spec(DbtVerb.BUILD, "")))
        assertEquals("dbt run --target prod", DbtCommandBuilder.buildDisplay(spec(DbtVerb.RUN, "   ")))
    }

    @Test
    fun `a selector is passed through as --select`() {
        assertEquals(
            "dbt build --select my_model --target prod",
            DbtCommandBuilder.buildDisplay(spec(DbtVerb.BUILD, "my_model")),
        )
    }

    @Test
    fun `only Preview requires a selector`() {
        // dbt show compiles a single node and has no whole-project form; the others do,
        // so the RUN button must stay enabled for them with an empty selector.
        assertTrue(DbtVerb.PREVIEW.requiresSelector)
        listOf(DbtVerb.RUN, DbtVerb.BUILD, DbtVerb.TEST, DbtVerb.COMPILE, DbtVerb.GENERATE_DOCS)
            .forEach { assertFalse(it.requiresSelector, "${it.display} must be runnable without a selector") }
    }
}
