package com.dbthelper.charts

import com.intellij.openapi.vfs.VirtualFile

/**
 * Decides whether a file is a dbt Charts board: a `.yml`/`.yaml` below the `charts/` directory of a
 * project whose root (the parent of `charts/`) holds `dbt_charts.yml`. dct hardcodes that directory
 * name (`CHARTS_SUBDIR`); `meta.yml` inside it is an inheritance-cascade file, not a board.
 *
 * Generic over the directory type so the rule is tested on `java.nio.file.Path` and run on the VFS.
 */
object DbtChartsBoardLocator {
    const val PROJECT_FILE = "dbt_charts.yml"
    private const val CHARTS_DIR = "charts"
    private val CASCADE_FILES = setOf("meta.yml", "meta.yaml")

    fun <D> isBoard(
        fileName: String,
        parent: D?,
        parentOf: (D) -> D?,
        nameOf: (D) -> String,
        hasChild: (D, String) -> Boolean,
    ): Boolean {
        if (!fileName.endsWith(".yml") && !fileName.endsWith(".yaml")) return false
        if (fileName in CASCADE_FILES) return false
        var dir = parent
        while (dir != null) {
            if (nameOf(dir) == CHARTS_DIR) {
                val root = parentOf(dir)
                if (root != null && hasChild(root, PROJECT_FILE)) return true
            }
            dir = parentOf(dir)
        }
        return false
    }

    fun isBoardFile(file: VirtualFile): Boolean =
        isBoard(file.name, file.parent, { it.parent }, { it.name }, { dir, name -> dir.findChild(name) != null })
}
