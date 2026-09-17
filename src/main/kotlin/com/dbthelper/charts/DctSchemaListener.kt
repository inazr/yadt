package com.dbthelper.charts

import com.intellij.util.messages.Topic
import java.nio.file.Path

/** Fired by [DctSchemaResolver] when the resolved dbt Charts board schema changes (null = none). */
interface DctSchemaListener {
    companion object {
        val TOPIC = Topic.create("dbt Charts schema changed", DctSchemaListener::class.java)
    }

    fun onSchemaChanged(schema: Path?)
}
