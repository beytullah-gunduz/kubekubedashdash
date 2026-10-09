package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.model.SessionId

/** What the person was about to do when the guard stopped them; the dialog takes its title and confirm button from it. */
enum class GuardVerb(val confirmLabel: String, val title: String) {
    CloseTab("Discard and close tab", "Close this tab?"),
    CloseWindow("Discard and close window", "Close this window?"),
    SwitchCluster("Discard and switch cluster", "Switch this tab's cluster?"),
    Quit("Discard and quit", "Quit KubeKubeDashDash?"),
}

/**
 * The question to put to the person: [edits] have unsaved work. [onDiscard] closes their editors
 * and goes on with the action; [onKeepEditing] brings the first editor to the front and drops the
 * action.
 */
data class DiscardPrompt(val verb: GuardVerb, val edits: List<DirtyEdit>, val onDiscard: () -> Unit, val onKeepEditing: () -> Unit)

/**
 * Stands between an action that would close editors (closing a cluster tab or the main window,
 * switching a tab's cluster, quitting) and the editors it would take down (D11).
 */
class UnsavedEditGuard(private val registry: YamlEditRegistry) {
    /**
     * No unsaved editor among those of [sessionIds]: closes their editors, runs [action] and returns
     * null. Otherwise returns the prompt to show and has done nothing yet.
     */
    fun guard(sessionIds: Collection<SessionId>, verb: GuardVerb, action: () -> Unit): DiscardPrompt? {
        val edits = registry.dirty(sessionIds)
        if (edits.isEmpty()) {
            closeEditors(sessionIds)
            action()
            return null
        }
        return DiscardPrompt(
            verb = verb,
            edits = edits,
            onDiscard = {
                closeEditors(sessionIds)
                action()
            },
            onKeepEditing = { registry.focus(edits.first().windowId) },
        )
    }

    private fun closeEditors(sessionIds: Collection<SessionId>) {
        for (sessionId in sessionIds.toSet()) registry.closeAllFor(sessionId)
    }
}
