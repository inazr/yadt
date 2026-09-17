package com.dbthelper.core.model

data class DbtExposure(
    val uniqueId: String,
    val name: String,
    val packageName: String,
    val originalFilePath: String,
    val description: String = "",
    val dependsOnNodes: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)
