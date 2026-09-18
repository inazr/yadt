package com.dbthelper.charts.preview

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The URL path under which `dct serve` renders a board: its path relative to `charts/`, without
 * the extension, each segment percent-encoded, with a trailing slash (`fin/q 1.yml` -> `/fin/q%201/`).
 */
object DctBoardUrl {
    fun of(relativePath: String): String {
        val withoutExtension = relativePath.replace('\\', '/').removeSuffix(".yml").removeSuffix(".yaml")
        val encoded = withoutExtension.split('/').joinToString("/") {
            URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20")
        }
        return "/$encoded/"
    }
}
