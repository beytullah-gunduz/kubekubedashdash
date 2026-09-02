package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The header filter is stored per session but means "filter THIS list":
 * it must clear whenever the main screen changes and survive detail-pane
 * open/close. Also pins the Cmd/Ctrl+F focus-request counter.
 */
class SessionViewModelSearchTest {

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
        shutdownCleanly(scope, label = "SessionViewModelSearchTest", manager = manager)
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
        viewModel.goBack()
        assertEquals(Screen.Main.Nodes(), viewModel.currentScreen.value)
        assertEquals("", viewModel.searchQuery.value)
        viewModel.setSearchQuery("kube")
        viewModel.goForward()
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `back from a detail pane to the same main screen keeps the filter`() {
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(detail)
        viewModel.setSearchQuery("nginx")
        viewModel.goBack()
        assertEquals(Screen.Main.Pods(), viewModel.currentScreen.value)
        assertEquals(null, viewModel.extraPaneScreen.value)
        assertEquals("nginx", viewModel.searchQuery.value)
    }

    @Test
    fun `back from a detail pane opened by a jump keeps the filter`() {
        viewModel.navigate(Screen.Main.Pods(selectPodUid = "uid-1"))
        viewModel.navigate(detail)
        viewModel.setSearchQuery("nginx")
        viewModel.goBack()
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
