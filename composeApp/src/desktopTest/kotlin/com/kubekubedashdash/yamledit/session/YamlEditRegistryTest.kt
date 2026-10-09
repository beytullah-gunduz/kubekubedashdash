package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.yamledit.TEST_NAMESPACE
import com.kubekubedashdash.yamledit.WriterMock
import com.kubekubedashdash.yamledit.YamlWriter
import com.kubekubedashdash.yamledit.configMapTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The process-wide editor registry: focus-or-create, the dirty listing the guards rely on, closing
 * by cluster tab and the scope each session runs on. Edit sessions load from the fabric8 CRUD mock;
 * Apply sessions need no cluster at all. The sessions run on `backgroundScope` and the test's
 * dispatcher. Loopback only; no real user state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YamlEditRegistryTest {

    private val context = "cluster-a"
    private val tab1 = SessionId("tab-1")
    private val tab2 = SessionId("tab-2")
    private lateinit var mock: WriterMock

    @BeforeTest
    fun setUp() {
        mock = WriterMock(context)
        mock.seedConfigMap("demo-cm")
        mock.seedConfigMap("other-cm")
    }

    @AfterTest
    fun tearDown() {
        mock.stop("YamlEditRegistryTest")
    }

    private fun TestScope.openEdit(registry: YamlEditRegistry, tab: SessionId = tab1, name: String = "demo-cm", editorContext: String = context, load: Boolean = true): YamlEditSession {
        val session = registry.openEdit(
            clusterSessionId = tab,
            context = editorContext,
            target = configMapTarget(name),
            writer = YamlWriter(mock.manager),
            feedback = RecordingFeedback(),
            masking = { false },
            bufferFactory = { StringEditorBuffer() },
            scopeFactory = { backgroundScope },
        )
        if (load) runCurrent()
        return session
    }

    private fun YamlEditSession.type(text: String) = (buffer as StringEditorBuffer).type(text)

    // ── Focus or create ────────────────────────────────────────────────────────

    @Test
    fun `opening the same object twice focuses the first editor instead of creating another`() = runTest {
        val registry = testRegistry()
        val first = openEdit(registry)
        assertEquals(0, first.focusRequests.value, "a new editor is shown by its window, not by a focus request")
        var buffersMade = 0

        val requests = mock.recorded {
            val second = registry.openEdit(tab1, context, configMapTarget(), YamlWriter(mock.manager), RecordingFeedback(), { false }, {
                buffersMade++
                StringEditorBuffer()
            }, { backgroundScope })
            runCurrent()
            assertSame(first, second)
        }

        assertEquals(1, first.focusRequests.value)
        assertEquals(listOf<EditorWindowModel>(first), registry.windows.value)
        assertEquals(0, buffersMade, "no buffer is made for an editor that already exists")
        assertEquals(emptyList(), requests.map { it.toString() }, "and nothing is loaded twice")
    }

    @Test
    fun `another object, tab or context gets its own editor with its own id`() = runTest {
        val registry = testRegistry()

        val a = openEdit(registry)
        val otherObject = openEdit(registry, name = "other-cm")
        val otherTab = openEdit(registry, tab = tab2)
        val otherContext = openEdit(registry, editorContext = "cluster-b")

        assertEquals(listOf<EditorWindowModel>(a, otherObject, otherTab, otherContext), registry.windows.value, "in the order they were opened")
        assertEquals(4, registry.windows.value.map { it.id }.toSet().size)
        assertEquals(registry.windows.value.map { it.id }.sorted(), registry.windows.value.map { it.id }, "ids only grow")
        assertEquals(0, a.focusRequests.value)
    }

    @Test
    fun `an opened edit session is started and loads its object`() = runTest {
        val registry = testRegistry()

        val session = openEdit(registry)

        assertEquals(EditPhase.Editing, session.phase.value)
        assertTrue(session.buffer.text().isNotEmpty())
    }

    @Test
    fun `an Apply editor is one per tab and context and focuses when opened again`() = runTest {
        val registry = testRegistry()

        val first = openApplyEditor(registry, "tab-1")
        val again = openApplyEditor(registry, "tab-1")
        val otherTab = openApplyEditor(registry, "tab-2")
        val otherContext = openApplyEditor(registry, "tab-1", context = "cluster-b")
        val edit = openEdit(registry)

        assertSame(first, again)
        assertEquals(1, first.focusRequests.value)
        assertNotSame(first, otherTab)
        assertNotSame(first, otherContext)
        assertEquals(listOf<EditorWindowModel>(first, otherTab, otherContext, edit), registry.windows.value, "an edit session and Apply sessions coexist")
    }

    @Test
    fun `editFor finds the editor of an object in one tab by its target key`() = runTest {
        val registry = testRegistry()
        val session = openEdit(registry)
        val key = configMapTarget().key

        assertSame(session, registry.editFor(tab1, key))
        assertNull(registry.editFor(tab2, key), "another tab has no such editor")
        assertNull(registry.editFor(tab1, configMapTarget("other-cm").key))

        session.close()

        assertNull(registry.editFor(tab1, key))
    }

    @Test
    fun `focus raises the focus counter of that window only`() = runTest {
        val registry = testRegistry()
        val a = openEdit(registry)
        val b = openEdit(registry, name = "other-cm")

        registry.focus(b.id)
        registry.focus(b.id)
        registry.focus(-1)

        assertEquals(0, a.focusRequests.value)
        assertEquals(2, b.focusRequests.value)
    }

    // ── Dirty ──────────────────────────────────────────────────────────────────

    @Test
    fun `dirty lists the editors with unsaved text from the live buffer, labelled`() = runTest {
        val registry = testRegistry()
        val clean = openEdit(registry)
        val typed = openEdit(registry, name = "other-cm")
        val apply = openApplyEditor(registry, "tab-2", text = "kind: ConfigMap\n")
        openApplyEditor(registry, "tab-2", context = "cluster-b") // an empty Apply editor is clean
        assertEquals(emptyList(), registry.dirty().filter { it.windowId == clean.id }.map { it.label })

        typed.type("# note\n")

        assertFalse(typed.dirty.value, "the debounced flag still lags")
        val all = registry.dirty()
        assertEquals(listOf(typed.id, apply.id), all.map { it.windowId })
        assertEquals(listOf("ConfigMap $TEST_NAMESPACE/other-cm", "Apply YAML ($context)"), all.map { it.label })
        assertEquals(listOf(tab1, tab2), all.map { it.clusterSessionId })
        assertEquals(listOf(context, context), all.map { it.context })
        assertEquals(listOf(typed.id), registry.dirty(listOf(tab1)).map { it.windowId })
        assertEquals(listOf(apply.id), registry.dirty(setOf(tab2)).map { it.windowId })
        assertEquals(emptyList(), registry.dirty(emptyList()))
        assertEquals(emptyList(), registry.dirty(listOf(SessionId("tab-3"))))
    }

    @Test
    fun `an editor that is still loading has nothing unsaved`() = runTest {
        val registry = testRegistry()

        val loading = openEdit(registry, load = false)

        assertEquals(EditPhase.Loading, loading.phase.value)
        assertEquals(emptyList(), registry.dirty())
    }

    @Test
    fun `an editor whose text is back at the seed is clean again`() = runTest {
        val registry = testRegistry()
        val session = openEdit(registry)
        val seeded = session.buffer.text()
        session.type("x")
        assertEquals(1, registry.dirty().size)

        (session.buffer as StringEditorBuffer).type(seeded, replace = true)

        assertEquals(emptyList(), registry.dirty())
    }

    // ── Closing ────────────────────────────────────────────────────────────────

    @Test
    fun `closeAllFor closes the editors of that tab only, dirty or not`() = runTest {
        val registry = testRegistry()
        val clean = openEdit(registry)
        val dirty = openEdit(registry, name = "other-cm")
        dirty.type("x")
        val apply = openApplyEditor(registry, "tab-1", text = "kind: ConfigMap\n")
        val elsewhere = openEdit(registry, tab = tab2)
        val elsewhereApply = openApplyEditor(registry, "tab-2")

        registry.closeAllFor(tab1)

        assertEquals(listOf<EditorWindowModel>(elsewhere, elsewhereApply), registry.windows.value)
        for (closed in listOf(clean, dirty, apply)) assertFalse(closed.closePrompt.value)
        assertEquals(EditPhase.Editing, elsewhere.phase.value)
        registry.focus(clean.id)
        assertEquals(0, clean.focusRequests.value, "a closed editor is no longer reachable by id")
    }

    @Test
    fun `closeAll closes every editor`() = runTest {
        val registry = testRegistry()
        openEdit(registry)
        openEdit(registry, tab = tab2)
        openApplyEditor(registry, "tab-1")

        registry.closeAll()

        assertEquals(emptyList(), registry.windows.value)
        assertEquals(emptyList(), registry.dirty())
    }

    @Test
    fun `a session that closes itself leaves the registry and the object can be opened afresh`() = runTest {
        val registry = testRegistry()
        val first = openEdit(registry)

        first.confirmClose()

        assertEquals(emptyList(), registry.windows.value)
        val second = openEdit(registry)
        assertNotSame(first, second)
        assertTrue(second.id > first.id)
        assertEquals(0, second.focusRequests.value)
    }

    @Test
    fun `a closed editor's polling stops with it`() = runTest {
        val registry = testRegistry()
        val session = openEdit(registry)
        assertEquals(EditPhase.Editing, session.phase.value)
        val polling = mock.recorded {
            advanceTimeBy(5_000)
            runCurrent()
        }
        assertEquals(listOf("GET"), polling.map { it.method }, "positive control: an open editor polls")

        registry.closeAll()
        val after = mock.recorded {
            advanceTimeBy(20_000)
            runCurrent()
        }

        assertEquals(emptyList(), after.map { it.toString() })
        assertTrue(backgroundScope.isActive, "a registry told not to cancel scopes leaves the test's scope alone")
    }

    // ── The scope a session runs on ────────────────────────────────────────────

    @Test
    fun `the registry cancels the scope it made for a session when the session closes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val registry = YamlEditRegistry(cancelScopeOnClose = true, io = dispatcher, compute = dispatcher)
        val made = mutableListOf<CoroutineScope>()
        val session = registry.openApply(
            clusterSessionId = tab1,
            context = context,
            defaultNamespace = "default",
            writer = YamlWriter(mock.manager),
            crds = { emptyList() },
            feedback = RecordingFeedback(),
            masking = { false },
            bufferFactory = { StringEditorBuffer() },
            scopeFactory = { CoroutineScope(SupervisorJob() + dispatcher).also { made += it } },
        )
        val scope = made.single()
        assertTrue(scope.isActive)

        session.close()

        assertFalse(scope.coroutineContext[Job]!!.isActive, "the registry made the scope, so it ends it")
    }

    @Test
    fun `a registry told not to cancel leaves the scope it was given running`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val registry = YamlEditRegistry(cancelScopeOnClose = false, io = dispatcher, compute = dispatcher)
        val owned = CoroutineScope(SupervisorJob() + dispatcher)
        val session = registry.openApply(tab1, context, "default", YamlWriter(mock.manager), { emptyList() }, RecordingFeedback(), { false }, { StringEditorBuffer() }, { owned })

        session.close()

        assertTrue(owned.isActive)
        assertEquals(emptyList(), registry.windows.value)
    }

    @Test
    fun `the app registry is one shared instance`() {
        assertSame(YamlEditRegistry.Default, YamlEditRegistry.Default)
    }
}
