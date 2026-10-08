package com.kubekubedashdash.model

import com.kubekubedashdash.yamledit.session.DirtyEdit
import com.kubekubedashdash.yamledit.session.DiscardPrompt
import com.kubekubedashdash.yamledit.session.GuardVerb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The window's unsaved-edit question: how a prompt is shown, answered and replaced. A prompt that
 * is dropped without an answer would leave a pending Cmd+Q waiting for ever, so every route off the
 * screen must answer it exactly once. Pure state; no windows and no real user state.
 */
class WorkspaceDiscardPromptTest {

    private val calls = mutableListOf<String>()
    private val workspace = Workspace()

    private fun prompt(tag: String, verb: GuardVerb = GuardVerb.CloseTab, onAnswer: () -> Unit = {}) = DiscardPrompt(
        verb = verb,
        edits = listOf(DirtyEdit(windowId = 1, clusterSessionId = SessionId("tab-1"), label = "ConfigMap example-ns/demo-cm", context = "cluster-a")),
        onDiscard = {
            onAnswer()
            calls += "$tag:discard"
        },
        onKeepEditing = {
            onAnswer()
            calls += "$tag:keep"
        },
    )

    @Test
    fun `a shown prompt is published and answering it removes it before the original callback runs`() {
        var shownDuringAnswer: DiscardPrompt? = null
        val wrapped = prompt("p", onAnswer = { shownDuringAnswer = workspace.discardPrompt.value }).dismissing(workspace)

        workspace.showDiscardPrompt(wrapped)
        assertSame(wrapped, workspace.discardPrompt.value)

        wrapped.onDiscard()

        assertEquals(listOf("p:discard"), calls)
        assertNull(shownDuringAnswer, "the prompt was already cleared when the action ran, so the action can close the window it was shown in")
        assertNull(workspace.discardPrompt.value)
    }

    @Test
    fun `keeping the edits clears the prompt and runs the original keep-editing callback`() {
        val wrapped = prompt("p").dismissing(workspace)
        workspace.showDiscardPrompt(wrapped)

        wrapped.onKeepEditing()

        assertEquals(listOf("p:keep"), calls)
        assertNull(workspace.discardPrompt.value)
    }

    @Test
    fun `a prompt answers once however often a button fires`() {
        val wrapped = prompt("p").dismissing(workspace)
        workspace.showDiscardPrompt(wrapped)

        wrapped.onDiscard()
        wrapped.onDiscard()
        wrapped.onKeepEditing()

        assertEquals(listOf("p:discard"), calls, "a double click must not close a second tab")
    }

    @Test
    fun `showing a second prompt answers the first one keep-editing and leaves the second shown`() {
        val first = prompt("first", GuardVerb.Quit).dismissing(workspace)
        val second = prompt("second", GuardVerb.CloseWindow).dismissing(workspace)
        workspace.showDiscardPrompt(first)

        workspace.showDiscardPrompt(second)

        assertEquals(listOf("first:keep"), calls, "a pending quit is cancelled, not dropped")
        assertSame(second, workspace.discardPrompt.value, "the first prompt's own clean-up must not clear the one that replaced it")
    }

    @Test
    fun `cancelling answers a shown prompt keep-editing and clears it`() {
        workspace.showDiscardPrompt(prompt("p", GuardVerb.Quit).dismissing(workspace))

        workspace.cancelDiscardPrompt()

        assertEquals(listOf("p:keep"), calls)
        assertNull(workspace.discardPrompt.value)
    }

    @Test
    fun `cancelling with nothing shown does nothing`() {
        workspace.cancelDiscardPrompt()

        assertEquals(emptyList(), calls)
        assertNull(workspace.discardPrompt.value)
    }

    @Test
    fun `a prompt shown without the wrapper is still cleared when it is cancelled or replaced`() {
        val bare = prompt("bare")
        workspace.showDiscardPrompt(bare)
        workspace.cancelDiscardPrompt()
        assertNull(workspace.discardPrompt.value)

        workspace.showDiscardPrompt(bare)
        val next = prompt("next")
        workspace.showDiscardPrompt(next)

        assertEquals(listOf("bare:keep", "bare:keep"), calls)
        assertSame(next, workspace.discardPrompt.value)
    }

    @Test
    fun `the wrapper keeps the verb and the list of editors`() {
        val original = prompt("p", GuardVerb.SwitchCluster)

        val wrapped = original.dismissing(workspace)

        assertEquals(GuardVerb.SwitchCluster, wrapped.verb)
        assertEquals(original.edits, wrapped.edits)
    }
}
