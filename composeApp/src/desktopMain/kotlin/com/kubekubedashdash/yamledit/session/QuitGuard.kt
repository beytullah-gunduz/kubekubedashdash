package com.kubekubedashdash.yamledit.session

/**
 * Decides a quit request (Cmd+Q on macOS reaches the app through the desktop quit
 * handler that the window layer installs) against the open editors. Logic only: it shows nothing
 * itself.
 */
object QuitGuard {
    /** True from the moment a prompt is shown until one of its two callbacks ran. */
    @Volatile
    private var pending = false

    /**
     * Nothing unsaved: [perform]. Otherwise [show]s a prompt whose discard closes every editor and
     * then [perform]s, and whose keep-editing brings the first unsaved editor to the front and
     * [cancel]s the quit. Each callback answers the prompt once; a second call of either is ignored.
     *
     * While a prompt is pending (shown, neither callback ran yet) a new request is ignored except
     * that the first unsaved editor is brought to the front: macOS hands back the same cached quit
     * response until it is answered, so a second Cmd+Q must not stack a second prompt.
     */
    fun onQuitRequested(registry: YamlEditRegistry, show: (DiscardPrompt) -> Unit, perform: () -> Unit, cancel: () -> Unit) {
        val edits = registry.dirty()
        if (pending) {
            edits.firstOrNull()?.let { registry.focus(it.windowId) }
            return
        }
        if (edits.isEmpty()) {
            perform()
            return
        }
        pending = true
        var answered = false
        val prompt = DiscardPrompt(
            verb = GuardVerb.Quit,
            edits = edits,
            onDiscard = {
                if (!answered) {
                    answered = true
                    pending = false
                    registry.closeAll()
                    perform()
                }
            },
            onKeepEditing = {
                if (!answered) {
                    answered = true
                    pending = false
                    registry.focus(edits.first().windowId)
                    cancel()
                }
            },
        )
        try {
            show(prompt)
        } catch (e: Throwable) {
            // A prompt that never appeared must not leave every later quit request ignored.
            pending = false
            throw e
        }
    }

    internal fun resetForTest() {
        pending = false
    }
}
