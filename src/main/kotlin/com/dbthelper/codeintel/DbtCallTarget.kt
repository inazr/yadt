package com.dbthelper.codeintel

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.DbtMacro
import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.DbtSource
import com.dbthelper.core.model.ManifestIndex
import com.intellij.psi.PsiFile

/** The manifest entity a `ref()` / `source()` / macro call in a dbt template points at. */
sealed class DbtCallTarget(val uniqueId: String, val originalFilePath: String, val displayName: String) {
    class Node(node: DbtNode) : DbtCallTarget(node.uniqueId, node.originalFilePath, node.name)
    class Source(source: DbtSource) :
        DbtCallTarget(source.uniqueId, source.originalFilePath, "${source.sourceName}.${source.name}")
    class Macro(macro: DbtMacro) : DbtCallTarget(macro.uniqueId, macro.originalFilePath, macro.name)
}

/** The node a `ref('name')` resolves to: matched by name or alias, never a test. */
fun ManifestIndex.findRefTarget(name: String): DbtNode? =
    nodes.values.firstOrNull { (it.name == name || it.alias == name) && it.resourceType != "test" }

fun ManifestIndex.findSource(sourceName: String, tableName: String): DbtSource? =
    sources.values.firstOrNull { it.sourceName == sourceName && it.name == tableName }

fun ManifestIndex.findMacro(name: String): DbtMacro? = macros.values.firstOrNull { it.name == name }

/** The resolvable call whose name (or, for sources, either argument) covers [offset]; refs first, then sources, then macros. */
fun findCallTargetAt(text: String, offset: Int, index: ManifestIndex): DbtCallTarget? =
    DbtJinjaUtils.findRefCalls(text).firstNotNullOfOrNull { ref ->
        if (offset in ref.nameRange) index.findRefTarget(ref.modelName)?.let { DbtCallTarget.Node(it) } else null
    } ?: DbtJinjaUtils.findSourceCalls(text).firstNotNullOfOrNull { src ->
        if (offset in src.sourceNameRange || offset in src.tableNameRange) {
            index.findSource(src.sourceName, src.tableName)?.let { DbtCallTarget.Source(it) }
        } else null
    } ?: DbtJinjaUtils.findMacroCalls(text).firstNotNullOfOrNull { macro ->
        if (offset in macro.nameRange) index.findMacro(macro.macroName)?.let { DbtCallTarget.Macro(it) } else null
    }

/** The loaded manifest when [file] gets dbt code intelligence, else null. */
fun codeIntelIndex(file: PsiFile): ManifestIndex? {
    val vFile = file.virtualFile ?: return null
    if (!isDbtCodeIntelFile(vFile)) return null
    return ManifestService.getInstance(file.project).getIndex().takeIf { it !== ManifestIndex.EMPTY }
}
