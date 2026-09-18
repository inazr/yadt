package com.dbthelper.charts.preview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DctPageStatusTest {

    @Test
    fun `a rendered board reloads itself through dct livereload`() = assertFalse(DctPageStatus.needsManualReload(200))

    @Test
    fun `dct's 500 traceback page has no livereload, so YADT must reload it`() =
        assertTrue(DctPageStatus.needsManualReload(500))

    @Test
    fun `a missing board page has no livereload either`() = assertTrue(DctPageStatus.needsManualReload(404))
}
