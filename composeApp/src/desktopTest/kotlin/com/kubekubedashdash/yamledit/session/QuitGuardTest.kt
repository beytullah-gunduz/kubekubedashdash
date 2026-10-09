package com.kubekubedashdash.yamledit.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cmd+Q against the open editors. [QuitGuard] keeps one process-wide "a prompt is pending" flag,
 * so every test starts and ends with it cleared. Loopback-free; no real user state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuitGuardTest {

    private val dirtyText = "kind: ConfigMap\n"

    @BeforeTest
    fun setUp() {
        QuitGuard.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        QuitGuard.resetForTest()
    }

    /** What a quit request did, in order. */
    private class Calls {
        val prompts = mutableListOf<DiscardPrompt>()
        val log = mutableListOf<String>()
    }

    private fun request(registry: YamlEditRegistry, calls: Calls, onPerform: () -> Unit = {}) {
        QuitGuard.onQuitRequested(
            registry,
            show = {
                calls.prompts += it
                calls.log += "show"
            },
            perform = {
                calls.log += "perform"
                onPerform()
            },
            cancel = { calls.log += "cancel" },
        )
    }

    @Test
    fun `with nothing unsaved the quit goes ahead without a prompt`() = runTest {
        val registry = testRegistry()
        val clean = openApplyEditor(registry, "tab-1")
        val calls = Calls()

        request(registry, calls)

        assertEquals(listOf("perform"), calls.log)
        assertEquals(listOf<EditorWindowModel>(clean), registry.windows.value, "the quit itself ends the process; the guard closes nothing")
    }

    @Test
    fun `with no editors at all the quit goes ahead`() = runTest {
        val calls = Calls()

        request(testRegistry(), calls)

        assertEquals(listOf("perform"), calls.log)
    }

    @Test
    fun `unsaved text shows the quit prompt and waits for the answer`() = runTest {
        val registry = testRegistry()
        val dirty = openApplyEditor(registry, "tab-1", text = dirtyText)
        openApplyEditor(registry, "tab-2", context = "cluster-b")
        val calls = Calls()

        request(registry, calls)

        assertEquals(listOf("show"), calls.log)
        val prompt = calls.prompts.single()
        assertEquals(GuardVerb.Quit, prompt.verb)
        assertEquals(listOf(dirty.id), prompt.edits.map { it.windowId })
        assertEquals(2, registry.windows.value.size, "nothing is closed before the answer")
    }

    @Test
    fun `discarding closes every editor and then quits`() = runTest {
        val registry = testRegistry()
        openApplyEditor(registry, "tab-1", text = dirtyText)
        openApplyEditor(registry, "tab-2", context = "cluster-b")
        val calls = Calls()
        var windowsWhenPerformed = -1
        request(registry, calls) { windowsWhenPerformed = registry.windows.value.size }

        calls.prompts.single().onDiscard()

        assertEquals(listOf("show", "perform"), calls.log)
        assertEquals(0, windowsWhenPerformed, "every editor, the clean one too, was closed before the quit")
        assertEquals(emptyList(), registry.windows.value)
    }

    @Test
    fun `keeping the edits cancels the quit and brings the unsaved editor to the front`() = runTest {
        val registry = testRegistry()
        val dirty = openApplyEditor(registry, "tab-1", text = dirtyText)
        val calls = Calls()
        request(registry, calls)

        calls.prompts.single().onKeepEditing()

        assertEquals(listOf("show", "cancel"), calls.log)
        assertEquals(1, dirty.focusRequests.value)
        assertEquals(listOf<EditorWindowModel>(dirty), registry.windows.value)
    }

    @Test
    fun `a second quit request while the prompt is pending is ignored but brings the editor to the front`() = runTest {
        val registry = testRegistry()
        val dirty = openApplyEditor(registry, "tab-1", text = dirtyText)
        val calls = Calls()
        request(registry, calls)
        assertEquals(0, dirty.focusRequests.value)

        request(registry, calls)
        request(registry, calls)

        assertEquals(listOf("show"), calls.log, "no second prompt, no perform, no cancel")
        assertEquals(2, dirty.focusRequests.value, "each repeat only focuses the first unsaved editor")
    }

    @Test
    fun `a repeat while the prompt is pending is ignored even when nothing is unsaved any more`() = runTest {
        val registry = testRegistry()
        val dirty = openApplyEditor(registry, "tab-1", text = dirtyText)
        val calls = Calls()
        request(registry, calls)
        (dirty.buffer as StringEditorBuffer).type("", replace = true)

        request(registry, calls)

        assertEquals(listOf("show"), calls.log, "the pending prompt is still the one on screen")
    }

    @Test
    fun `once the prompt is answered a new request is decided afresh`() = runTest {
        val registry = testRegistry()
        openApplyEditor(registry, "tab-1", text = dirtyText)
        val calls = Calls()
        request(registry, calls)
        calls.prompts.single().onKeepEditing()

        request(registry, calls)

        assertEquals(listOf("show", "cancel", "show"), calls.log)
        assertEquals(2, calls.prompts.size)

        calls.prompts.last().onDiscard()
        request(registry, calls)

        assertEquals(listOf("show", "cancel", "show", "perform", "perform"), calls.log, "after a discard there is nothing left to ask about")
    }

    @Test
    fun `an answer counts once however often the dialog reports it`() = runTest {
        val registry = testRegistry()
        openApplyEditor(registry, "tab-1", text = dirtyText)
        val calls = Calls()
        request(registry, calls)
        val prompt = calls.prompts.single()

        prompt.onDiscard()
        prompt.onDiscard()
        prompt.onKeepEditing()

        assertEquals(listOf("show", "perform"), calls.log)
    }

    @Test
    fun `a prompt that could not be shown does not leave every later quit ignored`() = runTest {
        val registry = testRegistry()
        openApplyEditor(registry, "tab-1", text = dirtyText)
        val calls = Calls()

        val failure = assertFailsWith<IllegalStateException> {
            QuitGuard.onQuitRequested(registry, show = { error("no window system") }, perform = { calls.log += "perform" }, cancel = { calls.log += "cancel" })
        }
        assertTrue(failure.message.orEmpty().isNotEmpty())

        request(registry, calls)

        assertEquals(listOf("show"), calls.log, "the next request is decided afresh and shows its prompt")
        assertNotNull(calls.prompts.singleOrNull())
    }
}
