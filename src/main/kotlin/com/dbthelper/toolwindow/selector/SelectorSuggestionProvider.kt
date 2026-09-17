package com.dbthelper.toolwindow.selector

import com.dbthelper.core.ManifestService
import com.dbthelper.core.ManifestUpdateListener
import com.dbthelper.core.model.ManifestIndex
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/**
 * Holds the current [SelectorCandidates] snapshot, rebuilt whenever the manifest changes.
 *
 * Subscribes to [ManifestUpdateListener.TOPIC] (per CLAUDE.md, UI code listens to the topic
 * instead of polling `ManifestService.getIndex()`); seeds once from the current index so
 * suggestions work before the next reparse. Recomputing pools only on manifest change keeps
 * per-keystroke matching cheap.
 */
class SelectorSuggestionProvider(project: Project, parentDisposable: Disposable) {

    @Volatile
    private var candidates: SelectorCandidates =
        SelectorCandidates.from(ManifestService.getInstance(project).getIndex())

    init {
        project.messageBus.connect(parentDisposable).subscribe(
            ManifestUpdateListener.TOPIC,
            object : ManifestUpdateListener {
                override fun onManifestUpdated(index: ManifestIndex) {
                    candidates = SelectorCandidates.from(index)
                }
            }
        )
    }

    fun suggest(context: SelectorTokenContext): List<SelectorSuggestion> =
        rankSelectorSuggestions(context, candidates)
}
