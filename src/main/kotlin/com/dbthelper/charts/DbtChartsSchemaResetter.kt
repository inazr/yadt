package com.dbthelper.charts

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import java.nio.file.Path

/** Makes open board editors pick up a newly resolved (or removed) schema. */
class DbtChartsSchemaResetter(private val project: Project) : DctSchemaListener {
    override fun onSchemaChanged(schema: Path?) {
        ApplicationManager.getApplication().invokeLater({
            JsonSchemaService.Impl.get(project).reset()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }, project.disposed)
    }
}
