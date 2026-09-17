package com.dbthelper.codeintel

import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.DbtUtils
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

class DbtGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        // Limit to reasonably-sized elements to avoid underlining the whole file
        if (element.textLength > 300) return null
        val file = element.containingFile ?: return null
        val index = codeIntelIndex(file) ?: return null

        val project = file.project
        val dbtRoot = DbtProjectLocator.getInstance(project).findProjectRoot() ?: return null
        val target = findCallTargetAt(file.text, offset, index) ?: return null
        return DbtUtils.resolveFile(project, dbtRoot.path, target.originalFilePath)?.let { arrayOf(it) }
    }
}
