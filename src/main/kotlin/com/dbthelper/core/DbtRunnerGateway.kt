package com.dbthelper.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Bridges the global "dbt: Run / Stop" and "dbt: Clear Output" actions to the
 * per-tool-window runner panel. The actions are project-wide and have no direct
 * handle on the panel (which is created per tool window), so DbtMainPanel
 * registers its run/clear handlers here on creation and the actions look them up.
 *
 * Handlers are null until the YADT tool window has been opened at least once, so
 * callers must activate the tool window first — its content creation instantiates
 * DbtMainPanel, which registers the handlers.
 */
@Service(Service.Level.PROJECT)
class DbtRunnerGateway {
    @Volatile private var onRun: (() -> Unit)? = null
    @Volatile private var onClear: (() -> Unit)? = null

    fun register(run: () -> Unit, clear: () -> Unit) {
        onRun = run
        onClear = clear
    }

    fun run() = onRun?.invoke()
    fun clear() = onClear?.invoke()

    companion object {
        fun getInstance(project: Project): DbtRunnerGateway = project.service()
    }
}
