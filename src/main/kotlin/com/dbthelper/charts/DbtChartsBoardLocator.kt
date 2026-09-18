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

    /** The `charts/` directory that makes this file a board, or null if it isn't one. */
    fun <D> chartsDir(
        fileName: String,
        parent: D?,
        parentOf: (D) -> D?,
        nameOf: (D) -> String,
        hasChild: (D, String) -> Boolean,
    ): D? {
        if (!fileName.endsWith(".yml") && !fileName.endsWith(".yaml")) return null
        if (fileName in CASCADE_FILES) return null
        var dir = parent
        while (dir != null) {
            if (nameOf(dir) == CHARTS_DIR) {
                val root = parentOf(dir)
                if (root != null && hasChild(root, PROJECT_FILE)) return dir
            }
            dir = parentOf(dir)
        }
        return null
    }

    fun <D> isBoard(
        fileName: String,
        parent: D?,
        parentOf: (D) -> D?,
        nameOf: (D) -> String,
        hasChild: (D, String) -> Boolean,
    ): Boolean = chartsDir(fileName, parent, parentOf, nameOf, hasChild) != null

    fun chartsDirOf(file: VirtualFile): VirtualFile? =
        chartsDir(file.name, file.parent, { it.parent }, { it.name }, { dir, name -> dir.findChild(name) != null })

    fun isBoardFile(file: VirtualFile): Boolean = chartsDirOf(file) != null
}
