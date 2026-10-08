package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.ui.screens.viewmodel.SessionViewModel
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import com.kubekubedashdash.yamledit.TEST_NAMESPACE
import com.kubekubedashdash.yamledit.WriterMock
import com.kubekubedashdash.yamledit.YamlWriter
import com.kubekubedashdash.yamledit.configMapTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * D11, the Esc / selection / back-forward requirement: an editor lives in its own window and in the
 * process-wide registry, so nothing the main window does to its screen, its detail pane or its
 * history can drop an unsaved buffer. The main window's view model is real; it is never connected
 * to a cluster (that would read the user's preferences and kubeconfig), because navigation does not
 * need a connection. Loopback only; no real user state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorsSurviveNavigationTest {

    private val context = "cluster-a"
    private val tab = SessionId("tab-1")
    private lateinit var mock: WriterMock
    private lateinit var viewScope: CoroutineScope
    private lateinit var viewManager: KubeConnectionManager
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun setUp() {
        mock = WriterMock(context)
        mock.seedConfigMap()
        viewScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        viewManager = KubeConnectionManager()
        viewModel = SessionViewModel(ReactiveKubeClient(viewScope, viewManager), viewScope)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(viewScope, label = "EditorsSurviveNavigationTest", manager = viewManager)
        mock.stop("EditorsSurviveNavigationTest")
    }

    private val detail = Screen.Detail.ResourceDetail(kind = "ConfigMap", name = "demo-cm", namespace = TEST_NAMESPACE)
    private val otherDetail = Screen.Detail.ResourceDetail(kind = "ConfigMap", name = "other-cm", namespace = TEST_NAMESPACE)

    @Test
    fun `selecting, closing the pane, leaving a screen and going back and forward leave an unsaved editor alone`() = runTest {
        val registry = testRegistry()
        val buffer = StringEditorBuffer()
        val session = registry.openEdit(tab, context, configMapTarget(), YamlWriter(mock.manager), RecordingFeedback(), { false }, { buffer }, { backgroundScope })
        runCurrent()
        buffer.type("# unsaved note\n")
        val typed = buffer.text()
        val base = session.base.value
        val apply = openApplyEditor(registry, "tab-1", text = "kind: ConfigMap\n")
        val applyText = apply.buffer.text()

        fun assertUntouched(step: String) {
            assertEquals(listOf<EditorWindowModel>(session, apply), registry.windows.value, step)
            assertSame(session, registry.editFor(tab, configMapTarget().key), step)
            assertEquals(typed, buffer.text(), step)
            assertEquals(applyText, apply.buffer.text(), step)
            assertEquals(EditPhase.Editing, session.phase.value, step)
            assertSame(base, session.base.value, step)
            assertTrue(session.isDirtyNow(), step)
            assertTrue(apply.isDirtyNow(), step)
            assertFalse(session.closePrompt.value, step)
            assertEquals(listOf(session.id, apply.id), registry.dirty().map { it.windowId }, step)
        }
        assertUntouched("before any navigation")

        viewModel.navigate(Screen.Main.ConfigMaps)
        assertEquals(Screen.Main.ConfigMaps, viewModel.currentScreen.value)
        assertUntouched("opening the ConfigMaps screen")

        viewModel.navigate(detail)
        assertEquals(detail, viewModel.extraPaneScreen.value)
        assertUntouched("opening the very object's detail pane")

        viewModel.navigate(otherDetail)
        assertEquals(otherDetail, viewModel.extraPaneScreen.value)
        assertUntouched("selecting another resource")

        viewModel.closeExtraPane()
        assertEquals(null, viewModel.extraPaneScreen.value)
        assertUntouched("closing the detail pane (Esc)")

        viewModel.navigate(Screen.Main.Pods())
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        assertUntouched("leaving the screen")

        viewModel.goBack()
        assertEquals(null, viewModel.extraPaneScreen.value)
        assertUntouched("back")
        viewModel.goBack()
        assertUntouched("back again")
        assertEquals(otherDetail, viewModel.extraPaneScreen.value, "positive control: the history really moved")
        viewModel.goForward()
        assertUntouched("forward")
        viewModel.goForward()
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        assertUntouched("forward again")

        // The editor is still fully usable afterwards: its text is what the person typed.
        assertTrue(buffer.text().endsWith("# unsaved note\n"))
        assertEquals(typed, buffer.text())

        // Negative control: the checks above would notice an editor that went away.
        registry.closeAll()
        assertFailsWith<AssertionError> { assertUntouched("after the editors were closed") }
    }
}
