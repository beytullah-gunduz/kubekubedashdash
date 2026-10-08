package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The header filter is stored per session but means "filter THIS list":
 * it must clear whenever the main screen changes and survive detail-pane
 * open/close. Also pins the Cmd/Ctrl+F focus-request counter.
 */
class SessionViewModelSearchTest {

    private lateinit var session: ClusterSession
    private lateinit var workspace: Workspace
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @BeforeTest
    fun setUp() {
        session = ClusterSession()
        workspace = Workspace()
        workspace.addSession(session, makeActive = true)
        viewModel = session.viewModel
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(session.scope, label = "SessionViewModelSearchTest", manager = session.connectionManager)
    }

    private val detail = Screen.Detail.ResourceDetail(kind = "Pod", name = "p1", namespace = "ns-a")

    @Test
    fun `navigating to another main screen clears the filter`() {
        viewModel.navigate(Screen.Main.Pods())
        viewModel.setSearchQuery("nginx")
        viewModel.navigate(Screen.Main.Deployments())
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `opening and closing a detail pane keeps the filter`() {
        viewModel.navigate(Screen.Main.Pods())
        viewModel.setSearchQuery("nginx")
        viewModel.navigate(detail)
        assertEquals("nginx", viewModel.searchQuery.value)
        viewModel.closeExtraPane()
        assertEquals("nginx", viewModel.searchQuery.value)
    }

    @Test
    fun `back and forward clear the filter when the main screen changes`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())
        viewModel.setSearchQuery("nginx")
        workspace.goBack()
        assertEquals(Screen.Main.Nodes(), viewModel.currentScreen.value)
        assertEquals("", viewModel.searchQuery.value)
        viewModel.setSearchQuery("kube")
        workspace.goForward()
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `back from a detail pane to the same main screen keeps the filter`() {
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(detail)
        viewModel.setSearchQuery("nginx")
        workspace.goBack()
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        assertEquals(null, viewModel.extraPaneScreen.value)
        assertEquals("nginx", viewModel.searchQuery.value)
    }

    @Test
    fun `back from a detail pane opened by a jump keeps the filter`() {
        viewModel.navigate(Screen.Main.Pods(selectPodUid = "uid-1"))
        viewModel.navigate(detail)
        viewModel.setSearchQuery("nginx")
        workspace.goBack()
        assertEquals(null, viewModel.extraPaneScreen.value)
        assertEquals("nginx", viewModel.searchQuery.value)
    }

    @Test
    fun `requestSearchFocus counts every press`() {
        assertEquals(0, viewModel.searchFocusRequests.value)
        viewModel.requestSearchFocus()
        viewModel.requestSearchFocus()
        assertEquals(2, viewModel.searchFocusRequests.value)
    }
}
