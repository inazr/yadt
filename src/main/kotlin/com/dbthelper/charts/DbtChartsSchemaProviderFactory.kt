package com.dbthelper.charts

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Applies the resolved dbt Charts schema to board files; the YAML plugin turns that into completion,
 * quick docs and structural errors. No schema resolved -> no provider, so boards stay plain YAML.
 * Registered only via yadt-charts-schema.xml (optional dependency on com.intellij.modules.json).
 */
class DbtChartsSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> {
        val schema = DctSchemaResolver.getInstance(project).schemaFile ?: return emptyList()
        return listOf(BoardSchemaProvider(schema))
    }

    private class BoardSchemaProvider(private val schema: VirtualFile) : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean = DbtChartsBoardLocator.isBoardFile(file)
        override fun getName(): String = "dbt Charts board"
        override fun getSchemaFile(): VirtualFile = schema
        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }
}
