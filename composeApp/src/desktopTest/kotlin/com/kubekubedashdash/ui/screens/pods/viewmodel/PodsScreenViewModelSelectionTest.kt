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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the selection funnel: [PodsScreenViewModel.setVisibleSelectable] and
 * [PodsScreenViewModel.setSelectedUids] intersect every write against the
 * last-reported visible set, so a hidden or departed pod can never end up
 * selected — regardless of which caller supplied the wider set.
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
    fun `selection is intersected with the visible set`() {
        vm.setVisibleSelectable(setOf("a", "b"))
        vm.setSelectedUids(setOf("a", "b", "c"))
        assertEquals(setOf("a", "b"), vm.selectedUids.value)
    }

    @Test
    fun `shrinking the visible set prunes the selection`() {
        vm.setVisibleSelectable(setOf("a", "b"))
        vm.setSelectedUids(setOf("a", "b"))
        vm.setVisibleSelectable(setOf("a"))
        assertEquals(setOf("a"), vm.selectedUids.value)
    }

    @Test
    fun `a retry handoff cannot resurrect a hidden pod`() {
        vm.setVisibleSelectable(setOf("a"))
        vm.setSelectedUids(setOf("a", "b"))
        assertEquals(setOf("a"), vm.selectedUids.value)
    }

    @Test
    fun `setParams clears both the selection and the visible set`() {
        vm.setVisibleSelectable(setOf("a", "b"))
        vm.setSelectedUids(setOf("a", "b"))
        assertTrue(vm.selectedUids.value.isNotEmpty())

        vm.setParams(null)
        vm.setSelectedUids(setOf("a"))
        assertTrue(vm.selectedUids.value.isEmpty())
    }
}
