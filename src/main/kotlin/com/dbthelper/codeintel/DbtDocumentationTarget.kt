package com.dbthelper.codeintel

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Offset-based V2 provider — runs BEFORE the PSI fallback. Required for DataSpell/PyCharm
 * where the bundled SQL plugin's own target provider would otherwise short-circuit the
 * PSI dispatch path that DbtPsiDocumentationTargetProvider sits on.
 */
class DbtDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> =
        listOfNotNull(documentationTargetAt(file, offset))
}

/**
 * PSI-based V2 fallback — works in IDEA Community (no SQL plugin hijack).
 * Kept as a secondary path.
 */
class DbtPsiDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        val context = originalElement ?: element
        val file = context.containingFile ?: return null
        return documentationTargetAt(file, context.textRange.startOffset)
    }
}

private fun documentationTargetAt(file: PsiFile, offset: Int): DbtDocumentationTarget? {
    val index = codeIntelIndex(file) ?: return null
    val target = findCallTargetAt(file.text, offset, index) ?: return null
    return DbtDocumentationTarget(file.project, target)
}

class DbtDocumentationTarget(
    private val project: Project,
    private val target: DbtCallTarget
) : DocumentationTarget {

    override fun createPointer(): Pointer<DbtDocumentationTarget> {
        val p = project
        val t = target
        return object : Pointer<DbtDocumentationTarget> {
            override fun dereference(): DbtDocumentationTarget = DbtDocumentationTarget(p, t)
        }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(target.displayName).presentation()

    override fun computeDocumentationHint(): String? = renderHtml()

    override fun computeDocumentation(): DocumentationResult? {
        val html = renderHtml() ?: return null
        return DocumentationResult.documentation(html)
    }

    private fun renderHtml(): String? {
        val index = ManifestService.getInstance(project).getIndex()
        if (index === ManifestIndex.EMPTY) return null
        // Re-read by id so the docs reflect the manifest at render time, not at hover time.
        return when (target) {
            is DbtCallTarget.Node -> index.nodes[target.uniqueId]?.let { DbtDocRenderer.buildNodeDoc(it, index) }
            is DbtCallTarget.Source -> index.sources[target.uniqueId]?.let { DbtDocRenderer.buildSourceDoc(it, index) }
            is DbtCallTarget.Macro -> index.macros[target.uniqueId]?.let { DbtDocRenderer.buildMacroDoc(it) }
        }
    }
}
