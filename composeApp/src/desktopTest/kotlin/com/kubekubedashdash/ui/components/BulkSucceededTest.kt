package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class BulkSucceededTest {
    private val items = listOf("a", "b", "c", "d")

    private fun finished(attempted: Int, failed: List<String> = emptyList(), cancelled: Boolean = false) = BulkRunState.Finished(
        verb = BulkVerbs.Scale,
        total = items.size,
        attempted = attempted,
        failures = failed.map { BulkFailure(it, "boom") },
        cancelled = cancelled,
    )

    @Test
    fun `a clean run applied every item`() {
        assertEquals(items, bulkSucceeded(items, finished(4)))
    }

    @Test
    fun `a failure in the middle is excluded`() {
        assertEquals(listOf("a", "c", "d"), bulkSucceeded(items, finished(4, failed = listOf("b"))))
    }

    @Test
    fun `a cancel after two of four applied only the first two`() {
        assertEquals(listOf("a", "b"), bulkSucceeded(items, finished(2, cancelled = true)))
    }

    @Test
    fun `a cancel with a failure among the attempted items`() {
        assertEquals(listOf("a", "c"), bulkSucceeded(items, finished(3, failed = listOf("b"), cancelled = true)))
    }
}
