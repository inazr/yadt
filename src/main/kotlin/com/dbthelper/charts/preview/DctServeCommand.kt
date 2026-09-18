package com.dbthelper.charts.preview

import java.nio.file.Path

/** `dct serve` for one dbt Charts project, bound to loopback on a port the IDE picked. */
object DctServeCommand {
    fun args(dct: Path, root: Path, port: Int): List<String> =
        listOf(dct.toString(), "serve", "--project-dir", root.toString(), "--host", "127.0.0.1", "--port", port.toString())
}
