package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.util.DemoContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppViewModelHasContextsTest {

    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun derivedFlow(contexts: MutableStateFlow<List<String>>) = contexts
        .map { list -> list.any { !DemoContext.isMockContext(it) } }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    @Test
    fun `false when only mock context present`() {
        val contexts = MutableStateFlow(listOf(DemoContext.MOCK_CONTEXT_NAME))
        assertFalse(derivedFlow(contexts).value)
    }

    @Test
    fun `false when mock-labeled instance context present`() {
        val contexts = MutableStateFlow(listOf("demo-cluster (mock) #1"))
        assertFalse(derivedFlow(contexts).value)
    }

    @Test
    fun `true when at least one non-mock context`() {
        val contexts = MutableStateFlow(
            listOf(DemoContext.MOCK_CONTEXT_NAME, "production-cluster"),
        )
        assertTrue(derivedFlow(contexts).value)
    }

    @Test
    fun `updates reactively when real context is added`() {
        val contexts = MutableStateFlow(listOf(DemoContext.MOCK_CONTEXT_NAME))
        val hasReal = derivedFlow(contexts)
        assertFalse(hasReal.value)
        contexts.value = listOf(DemoContext.MOCK_CONTEXT_NAME, "staging-cluster")
        assertTrue(hasReal.value)
    }

    @Test
    fun `updates reactively when real context is removed`() {
        val contexts = MutableStateFlow(
            listOf(DemoContext.MOCK_CONTEXT_NAME, "staging-cluster"),
        )
        val hasReal = derivedFlow(contexts)
        assertTrue(hasReal.value)
        contexts.value = listOf(DemoContext.MOCK_CONTEXT_NAME)
        assertFalse(hasReal.value)
    }
}
