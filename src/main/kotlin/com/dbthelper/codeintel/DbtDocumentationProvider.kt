package com.dbthelper.codeintel

import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.*
import com.dbthelper.core.toUnixPath
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class DbtDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        // element = resolved target file (from PsiReference.resolve())
        // When hovering over ref('model'), element is the model's .sql file
        if (element is PsiFile) {
            val vFile = element.virtualFile ?: return null
            val project = element.project
            val service = ManifestService.getInstance(project)
            val index = service.getIndex()
            if (index === ManifestIndex.EMPTY) return null

            val relativePath = DbtProjectLocator.getInstance(project).getRelativePath(vFile) ?: return null
            val normalized = relativePath.toUnixPath()

            val nodeId = index.findByFilePath(normalized)
            if (nodeId != null) {
                val node = index.nodes[nodeId]
                if (node != null) return DbtDocRenderer.buildNodeDoc(node, index)
            }

            val sourceMatch = findSourceFromContext(originalElement, index)
            if (sourceMatch != null) return DbtDocRenderer.buildSourceDoc(sourceMatch, index)

            for ((_, macro) in index.macros) {
                if (macro.originalFilePath.toUnixPath() == normalized) {
                    return DbtDocRenderer.buildMacroDoc(macro)
                }
            }
        }

        return null
    }

    private fun findSourceFromContext(originalElement: PsiElement?, index: ManifestIndex): DbtSource? {
        if (originalElement == null) return null
        val file = originalElement.containingFile ?: return null
        val text = file.text
        val offset = originalElement.textRange.startOffset

        val sourceCalls = DbtJinjaUtils.findSourceCalls(text)
        // At offset 0 (no caret position inside a call) fall back to the file's first source call.
        val call = sourceCalls.firstOrNull { offset in it.sourceNameRange || offset in it.tableNameRange }
            ?: sourceCalls.firstOrNull()?.takeIf { offset == 0 }
            ?: return null
        return index.findSource(call.sourceName, call.tableName)
    }
}