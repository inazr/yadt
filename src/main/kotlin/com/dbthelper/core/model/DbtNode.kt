package com.dbthelper.core.model

data class DbtNode(
    val uniqueId: String,
    val name: String,
    val resourceType: String,
    val packageName: String,
    val originalFilePath: String,
    val database: String? = null,
    val schema: String? = null,
    val alias: String? = null,
    val description: String = "",
    val columns: Map<String, DbtColumn> = emptyMap(),
    val dependsOnNodes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rawCode: String? = null,
    val compiledCode: String? = null,
    val config: Map<String, Any?> = emptyMap(),
    val fqn: List<String> = emptyList(),
    val patchPath: String? = null
) {
    /** The documenting yml's project-relative path: patch_path without its "package://" prefix. */
    val patchFilePath: String?
        get() = patchPath?.substringAfter("://")

    /** `[database.]schema.identifier` as dbt builds the relation, skipping missing parts. */
    fun qualifiedName(includeDatabase: Boolean = true): String =
        listOfNotNull(database.takeIf { includeDatabase }, schema, alias ?: name).joinToString(".")

    /** Lower-cased fully qualified relation, or null unless database and schema are known. */
    val relationName: String?
        get() = if (database == null || schema == null) null else qualifiedName().lowercase()
}
