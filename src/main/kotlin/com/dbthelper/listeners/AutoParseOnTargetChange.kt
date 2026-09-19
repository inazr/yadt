package com.dbthelper.listeners

import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.settings.SettingsChangeListener
import com.intellij.openapi.project.Project

/**
 * Re-parses when the dbt target selected in YADT changes: the manifest's relations (and every
 * `ref()` resolved from them, e.g. by dbt Charts) belong to the target it was parsed with.
 */
class AutoParseOnTargetChange(private val project: Project) : SettingsChangeListener {

    private var lastTarget = DbtHelperSettings.getInstance(project).state.activeTarget

    override fun onSettingsChanged() {
        val target = DbtHelperSettings.getInstance(project).state.activeTarget
        if (target == lastTarget) return
        lastTarget = target
        AutoParser.getInstance(project).request()
    }
}
