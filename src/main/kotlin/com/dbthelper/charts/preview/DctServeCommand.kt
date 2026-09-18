package com.dbthelper.charts.preview

import java.nio.file.Path

/**
 * `dct serve` for one dbt Charts project, bound to loopback on a port the IDE picked. [target] is
 * the Runner's dbt target (blank = dct's own default: `DBT_TARGET`, then the profile default).
 */
object DctServeCommand {
    fun args(dct: Path, root: Path, port: Int, target: String): List<String> =
        listOf(dct.toString(), "serve", "--project-dir", root.toString(), "--host", "127.0.0.1", "--port", port.toString()) +
            if (target.isBlank()) emptyList() else listOf("--target", target)
}
