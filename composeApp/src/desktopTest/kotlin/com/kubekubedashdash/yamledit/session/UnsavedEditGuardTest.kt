package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.model.SessionId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The guard between "close this tab / window, switch the cluster" and the editors that action
 * would take down. Apply editors stand in for both kinds: the guard only asks the registry, and
 * an Apply editor needs no cluster to be clean or dirty. Loopback-free; no real user state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsavedEditGuardTest {

    private val tab1 = SessionId("tab-1")
    private val tab2 = SessionId("tab-2")
    private val dirtyText = "kind: ConfigMap\n"

    @Test
    fun `with nothing unsaved the action runs and the editors of those tabs close`() = runTest {
        val registry = testRegistry()
        openApplyEditor(registry, "tab-1")
        openApplyEditor(registry, "tab-1", context = "cluster-b")
        val elsewhere = openApplyEditor(registry, "tab-2", text = dirtyText)
        var ran = 0

        val prompt = UnsavedEditGuard(registry).guard(listOf(tab1), GuardVerb.CloseTab) { ran++ }

        assertNull(prompt)
        assertEquals(1, ran)
        assertEquals(listOf<EditorWindowModel>(elsewhere), registry.windows.value, "only the clean editors of the tab went; another tab's editor is untouched")
    }

    @Test
    fun `with unsaved text it returns the prompt and does nothing yet`() = runTest {
        val registry = testRegistry()
        val clean = openApplyEditor(registry, "tab-1", context = "cluster-b")
        val dirty = openApplyEditor(registry, "tab-1", text = dirtyText)
        var ran = 0

        val prompt = assertNotNull(UnsavedEditGuard(registry).guard(listOf(tab1), GuardVerb.CloseWindow) { ran++ })

        assertEquals(0, ran, "the action waits for the answer")
        assertEquals(GuardVerb.CloseWindow, prompt.verb)
        assertEquals(listOf(dirty.id), prompt.edits.map { it.windowId }, "only the editor with unsaved work is listed")
        assertEquals(listOf("Apply YAML (cluster-a)"), prompt.edits.map { it.label })
        assertEquals(listOf<EditorWindowModel>(clean, dirty), registry.windows.value, "no editor was closed")
    }

    @Test
    fun `discarding closes every editor of the tabs and then runs the action`() = runTest {
        val registry = testRegistry()
        openApplyEditor(registry, "tab-1", context = "cluster-b")
        openApplyEditor(registry, "tab-1", text = dirtyText)
        val elsewhere = openApplyEditor(registry, "tab-2", text = dirtyText)
        var windowsWhenRun = -1

        val prompt = assertNotNull(UnsavedEditGuard(registry).guard(listOf(tab1), GuardVerb.SwitchCluster) { windowsWhenRun = registry.windows.value.size })
        prompt.onDiscard()

        assertEquals(1, windowsWhenRun, "the editors were closed before the action ran")
        assertEquals(listOf<EditorWindowModel>(elsewhere), registry.windows.value)
    }

    @Test
    fun `keeping the edits focuses the first unsaved editor and runs nothing`() = runTest {
        val registry = testRegistry()
        val first = openApplyEditor(registry, "tab-1", text = dirtyText)
        val second = openApplyEditor(registry, "tab-1", context = "cluster-b", text = dirtyText)
        var ran = 0

        val prompt = assertNotNull(UnsavedEditGuard(registry).guard(listOf(tab1), GuardVerb.CloseTab) { ran++ })
        prompt.onKeepEditing()

        assertEquals(0, ran)
        assertEquals(1, first.focusRequests.value)
        assertEquals(0, second.focusRequests.value)
        assertEquals(listOf<EditorWindowModel>(first, second), registry.windows.value)
    }

    @Test
    fun `a dirty editor of another tab neither blocks nor closes`() = runTest {
        val registry = testRegistry()
        val mine = openApplyEditor(registry, "tab-1")
        val theirs = openApplyEditor(registry, "tab-2", text = dirtyText)
        var ran = 0

        val prompt = UnsavedEditGuard(registry).guard(listOf(tab1), GuardVerb.CloseTab) { ran++ }

        assertNull(prompt)
        assertEquals(1, ran)
        assertEquals(listOf<EditorWindowModel>(theirs), registry.windows.value)
        assertEquals(0, mine.focusRequests.value)
    }

    @Test
    fun `several tabs at once list the unsaved editors of all of them and discard closes them all`() = runTest {
        val registry = testRegistry()
        val a = openApplyEditor(registry, "tab-1", text = dirtyText)
        val b = openApplyEditor(registry, "tab-2", text = dirtyText)
        val other = openApplyEditor(registry, "tab-3")
        var ran = 0

        val prompt = assertNotNull(UnsavedEditGuard(registry).guard(listOf(tab1, tab2), GuardVerb.CloseWindow) { ran++ })
        assertEquals(listOf(a.id, b.id), prompt.edits.map { it.windowId })
        prompt.onDiscard()

        assertEquals(1, ran)
        assertEquals(listOf<EditorWindowModel>(other), registry.windows.value)
    }

    @Test
    fun `no tabs means nothing is asked and nothing is closed`() = runTest {
        val registry = testRegistry()
        val dirty = openApplyEditor(registry, "tab-1", text = dirtyText)
        var ran = 0

        val prompt = UnsavedEditGuard(registry).guard(emptyList(), GuardVerb.CloseWindow) { ran++ }

        assertNull(prompt)
        assertEquals(1, ran)
        assertSame(dirty, registry.windows.value.single(), "an empty set of tabs does not mean every tab")
    }

    @Test
    fun `the decision uses the live buffer, not the debounced flag`() = runTest {
        val registry = testRegistry()
        val editor = openApplyEditor(registry, "tab-1")
        (editor.buffer as StringEditorBuffer).type("k")

        val prompt = UnsavedEditGuard(registry).guard(listOf(tab1), GuardVerb.CloseTab) {}

        assertNotNull(prompt, "a keystroke a moment ago already counts")
        assertEquals(false, editor.dirty.value)
    }

    @Test
    fun `the verbs carry the dialog texts`() {
        assertEquals("Discard and close tab", GuardVerb.CloseTab.confirmLabel)
        assertEquals("Close this tab?", GuardVerb.CloseTab.title)
        assertEquals("Discard and close window", GuardVerb.CloseWindow.confirmLabel)
        assertEquals("Close this window?", GuardVerb.CloseWindow.title)
        assertEquals("Discard and switch cluster", GuardVerb.SwitchCluster.confirmLabel)
        assertEquals("Switch this tab's cluster?", GuardVerb.SwitchCluster.title)
        assertEquals("Discard and quit", GuardVerb.Quit.confirmLabel)
        assertEquals("Quit KubeKubeDashDash?", GuardVerb.Quit.title)
    }
}
