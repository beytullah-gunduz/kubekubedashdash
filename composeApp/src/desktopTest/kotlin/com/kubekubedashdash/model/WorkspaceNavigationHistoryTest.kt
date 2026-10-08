package com.kubekubedashdash.model

import com.kubekubedashdash.Screen
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One Back/Forward history per window, across its cluster tabs. Real
 * [Workspace] and (unconnected) [ClusterSession]s: both sessions stay on the
 * Connecting screen until a test navigates them, as the session view-model
 * history tests do.
 */
class WorkspaceNavigationHistoryTest {
    private val a = ClusterSession()
    private val b = ClusterSession()
    private val w = Workspace()
    private val pod = Screen.Detail.ResourceDetail(kind = "Pod", name = "p1", namespace = "ns-a")
    private val keyA = "cluster:${a.id.value}"
    private val keyB = "cluster:${b.id.value}"

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @BeforeTest
    fun setUp() {
        w.addSession(a, makeActive = true)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(a.scope, b.scope, label = "WorkspaceNavigationHistoryTest")
        a.connectionManager.close()
        b.connectionManager.close()
    }

    @Test
    fun `back twice from another tab's second screen returns to the pod in the first tab`() {
        a.viewModel.navigate(Screen.Main.Pods())
        a.viewModel.navigate(pod)
        w.addSession(b, makeActive = true)
        b.viewModel.navigate(Screen.Main.ClusterOverview)
        b.viewModel.navigate(Screen.Main.Nodes())

        w.goBack()
        assertEquals(keyB, w.activeTabKey.value)
        assertEquals(Screen.Main.ClusterOverview, b.viewModel.currentScreen.value)

        w.goBack()
        assertEquals(keyA, w.activeTabKey.value)
        assertEquals(Screen.Main.Pods(), a.viewModel.currentScreen.value)
        assertEquals(pod, a.viewModel.extraPaneScreen.value)

        w.goForward()
        assertEquals(keyB, w.activeTabKey.value)
        assertEquals(Screen.Main.ClusterOverview, b.viewModel.currentScreen.value)

        w.goForward()
        assertEquals(keyB, w.activeTabKey.value)
        assertEquals(Screen.Main.Nodes(), b.viewModel.currentScreen.value)
    }

    @Test
    fun `a background tab's navigation is not recorded`() {
        a.viewModel.navigate(Screen.Main.Pods())
        a.viewModel.navigate(Screen.Main.Nodes())
        w.addSession(b, makeActive = false)

        b.viewModel.navigate(Screen.Main.Deployments())

        val back = w.navigationHistory.value.back
        assertEquals(1, back.size)
        assertEquals(keyA, back.single().tabKey)
    }

    @Test
    fun `closing a tab drops its entries`() {
        a.viewModel.navigate(Screen.Main.Pods())
        w.addSession(b, makeActive = true)
        b.viewModel.navigate(Screen.Main.ClusterOverview)
        b.viewModel.navigate(Screen.Main.Nodes())
        w.setActive(keyA)

        w.removeTab(keyB)

        val state = w.navigationHistory.value
        assertTrue((state.back + state.forward).none { it.tabKey == keyB })
        repeat(3) {
            w.goBack()
            assertEquals(keyA, w.activeTabKey.value)
        }
    }

    @Test
    fun `switching away and straight back leaves Back as it was`() {
        a.viewModel.navigate(Screen.Main.Nodes())
        a.viewModel.navigate(Screen.Main.Pods())
        val before = w.navigationHistory.value.back
        assertEquals(1, before.size)
        w.addSession(b, makeActive = false)

        w.setActive(keyB)
        w.setActive(keyA)

        assertEquals(before, w.navigationHistory.value.back)
    }

    @Test
    fun `Back waits while the active tab is on a connection screen`() {
        a.viewModel.navigate(Screen.Main.Pods())
        w.addSession(b, makeActive = true)
        assertEquals(1, w.navigationHistory.value.back.size)

        w.goBack()

        assertEquals(keyB, w.activeTabKey.value)
        assertEquals(1, w.navigationHistory.value.back.size)
    }

    @Test
    fun `clearNavigationHistory empties both stacks`() {
        a.viewModel.navigate(Screen.Main.Pods())
        a.viewModel.navigate(Screen.Main.Nodes())
        w.goBack()
        assertTrue(w.navigationHistory.value.canGoForward)

        w.clearNavigationHistory()

        assertEquals(NavigationHistoryState(), w.navigationHistory.value)
    }

    @Test
    fun `a fresh connect to another cluster drops that tab's screen entries`() {
        a.viewModel.navigate(Screen.Main.Pods())
        a.viewModel.navigate(Screen.Main.Nodes())
        assertEquals(
            listOf(HistoryLocation(keyA, "", Screen.Main.Pods(), null)),
            w.navigationHistory.value.back,
        )

        // The hook connectToCluster calls for a fresh connect; no real connect here
        // (SessionViewModelHistoryHostTest pins that wiring).
        a.viewModel.historyHost!!.contextReplaced(a.viewModel, "example-ctx")

        assertFalse(w.navigationHistory.value.back.any { it.tabKey == keyA && it.screen != null })
    }
}
