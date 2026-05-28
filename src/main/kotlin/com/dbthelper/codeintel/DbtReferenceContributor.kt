package com.dbthelper.codeintel

import com.dbthelper.core.DbtUtils
import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.ProcessingContext

class DbtReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(), DbtReferenceProvider())
    }
}

private data class CachedPatterns(
    val refs: List<DbtJinjaUtils.RefCall>,
    val sources: List<DbtJinjaUtils.SourceCall>,
    val macros: List<DbtJinjaUtils.MacroCall>
)

private class DbtReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        val vFile = file.virtualFile ?: return PsiReference.EMPTY_ARRAY
        if (!isDbtTemplateFile(vFile.name)) return PsiReference.EMPTY_ARRAY

        val patterns = CachedValuesManager.getCachedValue(file) {
            val text = file.text
            CachedValueProvider.Result.create(
                CachedPatterns(
                    DbtJinjaUtils.findRefCalls(text),
                    DbtJinjaUtils.findSourceCalls(text),
                    DbtJinjaUtils.findMacroCalls(text)
                ),
                file
            )
        }

        val elemRange = element.textRange
        val references = mutableListOf<PsiReference>()

        for (ref in patterns.refs) {
            rangeIfContained(ref.nameRange, elemRange)?.let { relRange ->
                references.add(DbtPsiReference(element, relRange) { index ->
                    index.nodes.values
                        .firstOrNull { (it.name == ref.modelName || it.alias == ref.modelName) && it.resourceType != "test" }
                        ?.originalFilePath
                })
            }
        }

        for (src in patterns.sources) {
            val sourceLookup: (ManifestIndex) -> String? = { index ->
                index.sources.values
                    .firstOrNull { it.sourceName == src.sourceName && it.name == src.tableName }
                    ?.originalFilePath
            }
            rangeIfContained(src.tableNameRange, elemRange)?.let { relRange ->
                references.add(DbtPsiReference(element, relRange, sourceLookup))
            }
            rangeIfContained(src.sourceNameRange, elemRange)?.let { relRange ->
                references.add(DbtPsiReference(element, relRange, sourceLookup))
            }
        }

        for (macro in patterns.macros) {
            rangeIfContained(macro.nameRange, elemRange)?.let { relRange ->
                references.add(DbtPsiReference(element, relRange) { index ->
                    index.macros.values.firstOrNull { it.name == macro.macroName }?.originalFilePath
                })
            }
        }

        return references.toTypedArray()
    }

    private fun rangeIfContained(nameRange: IntRange, elemRange: TextRange): TextRange? {
        val start = nameRange.first
        val end = nameRange.last + 1
        if (start >= elemRange.startOffset && end <= elemRange.endOffset) {
            return TextRange(start - elemRange.startOffset, end - elemRange.startOffset)
        }
        return null
    }
}

/**
 * Resolves a dbt Jinja reference (ref / source / macro call) to its declaring file.
 * The lookup lambda runs against the current manifest index at resolve time, so
 * references stay correct across manifest reloads.
 */
private class DbtPsiReference(
    element: PsiElement,
    range: TextRange,
    private val lookup: (ManifestIndex) -> String?
) : PsiReferenceBase<PsiElement>(element, range, true) {
    override fun resolve(): PsiElement? {
        val project = element.project
        val service = ManifestService.getInstance(project)
        val dbtRoot = service.getLocator().findProjectRoot() ?: return null
        val originalFilePath = lookup(service.getIndex()) ?: return null
        return DbtUtils.resolveFile(project, dbtRoot.path, originalFilePath)
    }
}
