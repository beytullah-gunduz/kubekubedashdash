package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Pins the one bulk outcome that closes the dialog by itself and toasts. */
class CleanBulkFinishTest {

    private fun finished(failures: List<BulkFailure<String>> = emptyList(), cancelled: Boolean = false) = BulkRunState.Finished(BulkVerbs.Evict, total = 3, attempted = 3, failures = failures, cancelled = cancelled)

    @Test
    fun `a run that succeeded for every item and was not stopped is clean`() {
        val state = finished()
        assertSame(state, cleanBulkFinish(state))
    }

    @Test
    fun `a failure keeps the dialog`() {
        assertNull(cleanBulkFinish(finished(failures = listOf(BulkFailure("a", "forbidden")))))
    }

    @Test
    fun `a stopped run keeps the dialog`() {
        assertNull(cleanBulkFinish(finished(cancelled = true)))
    }

    @Test
    fun `running and absent states are not clean`() {
        assertNull(cleanBulkFinish(BulkRunState.Running(BulkVerbs.Evict, total = 3, done = 1, currentItemLabel = "a")))
        assertNull(cleanBulkFinish<String>(null))
    }
}
