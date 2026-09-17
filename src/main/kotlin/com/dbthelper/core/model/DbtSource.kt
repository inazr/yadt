package com.dbthelper.core.model

data class DbtSource(
    val uniqueId: String,
    val name: String,
    val sourceName: String,
    val packageName: String,
    val originalFilePath: String,
    val database: String? = null,
    val schema: String? = null,
    val identifier: String? = null,
    val description: String = "",
    val columns: Map<String, DbtColumn> = emptyMap(),
    val tags: List<String> = emptyList(),
    val loader: String? = null,
    val freshnessWarnAfter: String? = null,
    val freshnessErrorAfter: String? = null,
    val loadedAtField: String? = null
) {
    /** `[database.]schema.identifier` as dbt builds the relation, skipping missing parts. */
    fun qualifiedName(includeDatabase: Boolean = true): String =
        listOfNotNull(database.takeIf { includeDatabase }, schema, identifier ?: name).joinToString(".")

    /** Lower-cased fully qualified relation, or null unless database and schema are known. */
    val relationName: String?
        get() = if (database == null || schema == null) null else qualifiedName().lowercase()
}
