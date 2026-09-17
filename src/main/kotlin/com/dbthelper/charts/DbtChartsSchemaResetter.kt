package com.dbthelper.charts

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import java.nio.file.Path

/**
 * Makes open board editors pick up a newly resolved (or removed) schema. `reset()` re-queries the
 * provider factories and restarts highlighting itself, so no separate DaemonCodeAnalyzer restart.
 */
class DbtChartsSchemaResetter(private val project: Project) : DctSchemaListener {
    override fun onSchemaChanged(schema: Path?) {
        ApplicationManager.getApplication().invokeLater({
            JsonSchemaService.Impl.get(project).reset()
        }, project.disposed)
    }
}
