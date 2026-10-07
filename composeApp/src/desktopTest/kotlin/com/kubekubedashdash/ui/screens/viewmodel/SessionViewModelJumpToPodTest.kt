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
 * A jump to a pod (`Pods(selectPodUid)`) widens the session to All Namespaces
 * because the pod may live outside the selected one (a node's pod list is
 * cluster-wide). The header flow and the informers' flow must widen together:
 * widening only the header shows "All Namespaces" over a list still scoped to
 * the old namespace, where an out-of-scope pod never appears and the pending
 * selection never resolves.
 */
class SessionViewModelJumpToPodTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        client = ReactiveKubeClient(scope, manager)
        viewModel = SessionViewModel(client, scope)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "SessionViewModelJumpToPodTest", manager = manager)
    }

    @Test
    fun `a jump to a pod widens the informers along with the header`() {
        viewModel.setSelectedNamespace("ns-a")
        viewModel.navigate(Screen.Main.Pods(selectPodUid = "uid-in-ns-b"))
        assertEquals("All Namespaces", viewModel.selectedNamespace.value)
        assertEquals(null, client.selectedNamespace.value)
    }

    @Test
    fun `opening Pods without a jump keeps the namespace`() {
        viewModel.setSelectedNamespace("ns-a")
        viewModel.navigate(Screen.Main.Pods())
        assertEquals("ns-a", viewModel.selectedNamespace.value)
        assertEquals("ns-a", client.selectedNamespace.value)
    }
}
