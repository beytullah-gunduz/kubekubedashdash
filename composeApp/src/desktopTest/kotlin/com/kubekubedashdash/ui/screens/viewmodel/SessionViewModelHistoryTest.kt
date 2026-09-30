package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * History semantics for [SessionViewModel]'s back/forward navigation.
 */
class SessionViewModelHistoryTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        viewModel = SessionViewModel(ReactiveKubeClient(scope, manager), scope)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "SessionViewModelHistoryTest", manager = manager)
    }

    private val detail = Screen.Detail.ResourceDetail(kind = "Pod", name = "p1", namespace = "ns-a")

    // No shared builder exists in TST, so every required field is filled here with obviously
    // fake values (PaneContentKeyTest's pattern, plus a status to tell snapshots apart).
    private fun pod(uid: String, name: String = "web-0", namespace: String = "default", status: String = "Running") = PodInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        status = status,
        ready = "1/1",
        restarts = 0,
        age = "1h",
        node = "node-1",
        ip = "10.0.0.1",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = emptyList(),
    )

    @Test
    fun `back returns to the previous main screen`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.goBack()
        assertEquals(Screen.Main.Nodes(), viewModel.currentScreen.value)
        assertEquals(null, viewModel.extraPaneScreen.value)
    }

    @Test
    fun `back from an open detail closes the pane first, then leaves the screen`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(detail)
        assertEquals(detail, viewModel.extraPaneScreen.value)
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        viewModel.goBack()
        assertEquals(null, viewModel.extraPaneScreen.value)
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        viewModel.goBack()
        assertEquals(Screen.Main.Nodes(), viewModel.currentScreen.value)
        assertEquals(null, viewModel.extraPaneScreen.value)
    }

    @Test
    fun `forward replays what back undid`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(detail)
        viewModel.goBack()
        viewModel.goBack()
        viewModel.goForward()
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        assertEquals(null, viewModel.extraPaneScreen.value)
        viewModel.goForward()
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        assertEquals(detail, viewModel.extraPaneScreen.value)
    }

    @Test
    fun `a new navigation clears the forward stack`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.goBack()
        viewModel.navigate(Screen.Main.Deployments())
        viewModel.goForward()
        assertEquals(Screen.Main.Deployments(), viewModel.currentScreen.value)
        runBlocking { withTimeout(5_000) { viewModel.canGoForward.first { !it } } }
    }

    @Test
    fun `re-navigating to the current screen records nothing`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.goBack()
        assertEquals(Screen.Main.Nodes(), viewModel.currentScreen.value)
    }

    @Test
    fun `the transient Connecting screen is never recorded`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.goBack()
        assertEquals(Screen.Main.Nodes(), viewModel.currentScreen.value)
    }

    @Test
    fun `closing the extra pane is a history event — back reopens it`() {
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(detail)
        viewModel.closeExtraPane()
        assertEquals(null, viewModel.extraPaneScreen.value)
        viewModel.goBack()
        assertEquals(detail, viewModel.extraPaneScreen.value)
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
    }

    @Test
    fun `back is blocked on the connection-error screen`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.ConnectionError("boom", 10))
        viewModel.goBack()
        assertEquals(Screen.Main.ConnectionError("boom", 10), viewModel.currentScreen.value)
    }

    @Test
    fun `back on an empty stack is a no-op`() {
        viewModel.goBack()
        assertEquals(Screen.Main.Connecting, viewModel.currentScreen.value)
    }

    @Test
    fun `history depth is capped at 50 entries`() {
        viewModel.navigate(Screen.Main.Nodes())
        repeat(60) { i -> viewModel.navigate(if (i % 2 == 0) Screen.Main.Pods() else Screen.Main.Nodes()) }
        var changes = 0
        repeat(70) {
            val before = viewModel.currentScreen.value to viewModel.extraPaneScreen.value
            viewModel.goBack()
            if (viewModel.currentScreen.value to viewModel.extraPaneScreen.value != before) changes++
        }
        assertEquals(50, changes)
    }

    @Test
    fun `history drops the jump-to-pod parameter so a restored list does not re-open the pane`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods(selectPodUid = "abc"))
        viewModel.navigate(detail)
        viewModel.goBack()
        viewModel.goBack()
        assertEquals(Screen.Main.Nodes(), viewModel.currentScreen.value)
        viewModel.goForward()
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        assertEquals(null, viewModel.extraPaneScreen.value)
    }

    @Test
    fun `leaving a transient screen clears the forward stack`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.goBack()
        viewModel.navigate(Screen.Main.ConnectionError("boom", 10))
        viewModel.navigate(Screen.Main.Deployments())
        viewModel.goForward()
        assertEquals(Screen.Main.Deployments(), viewModel.currentScreen.value)
    }

    @Test
    fun `re-clicking the open resource after a tick refreshes the pane without a history entry`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(Screen.Detail.PodDetail(pod("u-1", status = "Running")))
        viewModel.navigate(Screen.Detail.PodDetail(pod("u-1", status = "Pending")))
        assertEquals("Pending", (viewModel.extraPaneScreen.value as Screen.Detail.PodDetail).pod.status)
        viewModel.goBack()
        assertEquals(null, viewModel.extraPaneScreen.value)
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
    }

    @Test
    fun `opening a different pod is still a history event`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(Screen.Detail.PodDetail(pod("u-1", name = "web-0")))
        viewModel.navigate(Screen.Detail.PodDetail(pod("u-2", name = "web-1")))
        viewModel.goBack()
        assertEquals("u-1", (viewModel.extraPaneScreen.value as Screen.Detail.PodDetail).pod.uid)
    }
}
