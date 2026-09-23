package com.dbthelper.charts.query

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Bridges the gutter icon to the Query tab, like [com.dbthelper.core.DbtRunnerGateway] does for the
 * Runner. The tab only exists once the YADT tool window has been shown, so a request that arrives
 * earlier is kept and replayed when the tab registers. EDT only.
 */
@Service(Service.Level.PROJECT)
class DbtQueryPreviewGateway {
    private var onPreview: ((VirtualFile, String) -> Unit)? = null
    private var pending: Pair<VirtualFile, String>? = null

    fun register(preview: (VirtualFile, String) -> Unit) {
        onPreview = preview
        pending?.let { (board, name) ->
            pending = null
            preview(board, name)
        }
    }

    fun preview(board: VirtualFile, queryName: String) {
        val handler = onPreview
        if (handler != null) handler(board, queryName) else pending = board to queryName
    }

    companion object {
        fun getInstance(project: Project): DbtQueryPreviewGateway = project.service()
    }
}
