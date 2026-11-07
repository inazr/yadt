package com.dbthelper.core

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * [name] in the project virtualenvs below [roots], for [ExecutableLocator.find]'s `preferred`.
 * Empty for an untrusted project: a cloned repo could ship its own `.venv/bin/dbt`, and YADT runs
 * dbt and dct without asking. Such projects fall back to the settings path, install dirs and PATH.
 */
object ProjectVenvs {
    fun find(project: Project, roots: List<Path>, name: String): List<Path> =
        if (!TrustedProjects.isProjectTrusted(project)) emptyList()
        else roots.flatMap { ExecutableLocator.projectVenvs(it, name) }.distinct()
}
