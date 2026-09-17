package com.dbthelper.codeintel

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class DbtAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        val index = codeIntelIndex(element) ?: return
        val text = element.text

        for (ref in DbtJinjaUtils.findRefCalls(text)) {
            val range = TextRange(ref.nameRange.first, ref.nameRange.last + 1)
            if (index.findRefTarget(ref.modelName) == null) {
                holder.newAnnotation(HighlightSeverity.WARNING, "Unresolved ref: '${ref.modelName}'")
                    .range(range)
                    .create()
            }
        }

        for (src in DbtJinjaUtils.findSourceCalls(text)) {
            val range = TextRange(src.fullRange.first, src.fullRange.last + 1)
            if (index.findSource(src.sourceName, src.tableName) == null) {
                holder.newAnnotation(HighlightSeverity.WARNING, "Unresolved source: '${src.sourceName}.${src.tableName}'")
                    .range(range)
                    .create()
            }
        }
    }
}
