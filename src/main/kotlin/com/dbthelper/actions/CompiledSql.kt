package com.dbthelper.actions

import com.dbthelper.core.DbtSelectorParser
import com.dbthelper.core.model.ManifestIndex
import java.io.File

/** Reads what `dbt compile` wrote under `target/compiled/<project>/`. */
object CompiledSql {

    /** [sql] to copy, and a [label] naming what it covers ("orders" or "3 models"). */
    data class Result(val sql: String, val label: String)

    /**
     * For a selector naming exactly one model, that model's compiled SQL; otherwise every model
     * that has a compiled file, each under a `-- <name>` header. Null when nothing was compiled.
     */
    fun collect(index: ManifestIndex, compiledDir: File, selector: String): Result? {
        val models = index.nodes.values.filter { it.resourceType == "model" }
        val baseName = DbtSelectorParser.parse(selector)?.modelName
        val single = baseName?.let { name -> models.singleOrNull { it.name == name } }
        if (single != null) {
            val file = File(compiledDir, single.originalFilePath)
            return if (file.isFile) Result(file.readText(), single.name) else null
        }
        val blocks = models.mapNotNull { node ->
            File(compiledDir, node.originalFilePath).takeIf { it.isFile }
                ?.let { "-- ${node.name}\n" + it.readText().trim() + "\n\n" }
        }
        return if (blocks.isEmpty()) null else Result(blocks.joinToString(""), "${blocks.size} models")
    }
}
