package com.dbthelper.charts.preview

import com.dbthelper.charts.DbtChartsBoardLocator
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp

/**
 * Opens dbt Charts boards like Markdown: text editor and a live `dct serve` preview side by side,
 * with the Editor / Split / Preview toolbar. Without JCEF, boards open in the plain YAML editor.
 */
class DbtChartsPreviewEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        DbtChartsBoardLocator.chartsDirOf(file) != null && JBCefApp.isSupported()

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val chartsDir = checkNotNull(DbtChartsBoardLocator.chartsDirOf(file)) { "$file is not a dbt Charts board" }
        val relative = checkNotNull(VfsUtilCore.getRelativePath(file, chartsDir)) { "$file is not below $chartsDir" }
        val preview = DbtChartsPreviewEditor(project, file, chartsDir.parent.toNioPath(), DctBoardUrl.of(relative))
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return TextEditorWithPreview(text, preview, "dbt Charts", TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
    }

    override fun getEditorTypeId(): String = "dbt-charts-preview"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
