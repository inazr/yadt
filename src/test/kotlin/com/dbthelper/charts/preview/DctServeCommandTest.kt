package com.dbthelper.charts.preview

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DctServeCommandTest {

    @Test
    fun `serves the project on loopback at the given port`() {
        assertEquals(
            listOf("/bin/dct", "serve", "--project-dir", "/work/proj", "--host", "127.0.0.1", "--port", "38123"),
            DctServeCommand.args(Path.of("/bin/dct"), Path.of("/work/proj"), 38123, target = ""),
        )
    }

    @Test
    fun `passes the selected dbt target`() {
        assertEquals(
            listOf("/bin/dct", "serve", "--project-dir", "/work/proj", "--host", "127.0.0.1", "--port", "38123", "--target", "prod"),
            DctServeCommand.args(Path.of("/bin/dct"), Path.of("/work/proj"), 38123, target = "prod"),
        )
    }
}
