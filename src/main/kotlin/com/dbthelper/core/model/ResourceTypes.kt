package com.dbthelper.core.model

/** Resource types dbt materializes (and a schema yml documents via patch_path). */
val BUILDABLE_RESOURCE_TYPES = setOf("model", "seed", "snapshot")
