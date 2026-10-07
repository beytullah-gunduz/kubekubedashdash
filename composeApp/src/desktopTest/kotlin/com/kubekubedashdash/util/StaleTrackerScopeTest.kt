package com.kubekubedashdash.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A row that left the list because the namespace selection stopped covering it
 * was not deleted, so the tracker must not keep it on screen as a ghost.
 */
class StaleTrackerScopeTest {

    private data class Row(val uid: String, val namespace: String)

    private lateinit var scope: CoroutineScope
    private lateinit var tracker: StaleTracker<Row>

    private val bothRows = mapOf("a" to Row("a", "ns-a"), "b" to Row("b", "ns-b"))

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        tracker = StaleTracker(scope, { it.uid }, { Duration.ofMinutes(5) })
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a departure outside the scope does not linger`() {
        tracker.onSnapshot(bothRows)

        val stale = tracker.onSnapshot(emptyMap()) { it.namespace == "ns-a" }

        assertEquals(setOf("a"), stale.keys)
    }

    @Test
    fun `retain drops lingering rows outside the scope`() = runBlocking {
        tracker.onSnapshot(bothRows)
        tracker.onSnapshot(emptyMap())

        tracker.retain { it.namespace == "ns-a" }

        val remaining = withTimeout(5_000) { tracker.stale.first { it.keys == setOf("a") } }

        assertEquals(Row("a", "ns-a"), remaining["a"])
    }
}
