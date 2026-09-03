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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the detail-pane expand flag and the nullable last-dragged width on
 * [SessionViewModel]. No connection is made: navigation and the pane state
 * are pure view-model writes.
 */
class SessionViewModelExpandTest {

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
        shutdownCleanly(scope, label = "SessionViewModelExpandTest", manager = manager)
    }

    private val detail = Screen.Detail.ResourceDetail(kind = "Pod", name = "p1", namespace = "ns-a")

    @Test
    fun `expand is ignored while no pane is open`() {
        viewModel.setExtraPaneExpanded(true)
        assertFalse(viewModel.extraPaneExpanded.value)
    }

    @Test
    fun `expand applies to an open pane and closing the pane resets it`() {
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(detail)
        viewModel.setExtraPaneExpanded(true)
        assertTrue(viewModel.extraPaneExpanded.value)

        viewModel.closeExtraPane()
        assertNull(viewModel.extraPaneScreen.value)
        assertFalse(viewModel.extraPaneExpanded.value)
    }

    @Test
    fun `a main-screen navigation drops the pane and the expand flag`() {
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(detail)
        viewModel.setExtraPaneExpanded(true)

        viewModel.navigate(Screen.Main.Nodes())
        assertNull(viewModel.extraPaneScreen.value)
        assertFalse(viewModel.extraPaneExpanded.value)
    }

    @Test
    fun `back onto an entry without a pane resets the expand flag`() {
        viewModel.navigate(Screen.Main.Pods())
        viewModel.navigate(detail)
        viewModel.setExtraPaneExpanded(true)

        viewModel.goBack()
        assertNull(viewModel.extraPaneScreen.value)
        assertFalse(viewModel.extraPaneExpanded.value)
        // Forward reopens the pane, collapsed: expanding is a view choice, not history.
        viewModel.goForward()
        assertEquals(detail, viewModel.extraPaneScreen.value)
        assertFalse(viewModel.extraPaneExpanded.value)
    }

    @Test
    fun `the last dragged width is null until a drag and clamps like before`() {
        assertNull(viewModel.extraPaneWidth.value)
        viewModel.setExtraPaneWidth(5_000f)
        assertEquals(1200f, viewModel.extraPaneWidth.value)
        viewModel.setExtraPaneWidth(10f)
        assertEquals(400f, viewModel.extraPaneWidth.value)
    }
}
