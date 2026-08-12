package com.kubekubedashdash.ui.screens.pods.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers [PodsScreenViewModel.setParams] resetting the shared
 * [com.kubekubedashdash.ui.components.SelectionFunnel]: the funnel's own
 * intersection behavior is covered by SelectionFunnelTest; this class only
 * verifies the VM wires setParams to selection.reset().
 *
 * Constructed exactly like GkeDiscoveryViewModelTest: a real
 * ReactiveKubeClient(scope, KubeConnectionManager()) whose connection
 * resolves lazily, so no kubeconfig or network is touched.
 */
class PodsScreenViewModelSelectionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var reactiveClient: ReactiveKubeClient
    private lateinit var vm: PodsScreenViewModel

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        reactiveClient = ReactiveKubeClient(scope, manager)
        vm = PodsScreenViewModel(reactiveClient)
    }

    @AfterTest
    fun tearDown() {
        vm.viewModelScope.cancel()
        scope.cancel()
        manager.close()
    }

    @Test
    fun `setParams clears both the selection and the visible set`() {
        vm.selection.setVisible(setOf("a"))
        vm.selection.set(setOf("a"))
        assertTrue(vm.selection.selected.value.isNotEmpty())

        vm.setParams(null)
        vm.selection.set(setOf("a"))
        assertTrue(vm.selection.selected.value.isEmpty())
    }
}
