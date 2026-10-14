package com.kubekubedashdash.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkRunTest {

    private fun run(state: String, submittedAt: String = "2026-01-01T10:00:00Z", terminatedAt: String = "2026-01-01T10:12:30Z") = SparkRun(
        driverPod = "app-driver",
        executorPods = emptyList(),
        submittedAt = submittedAt,
        terminatedAt = terminatedAt,
        state = state,
    )

    @Test
    fun `a completed or failed application ran from its submission to its termination`() {
        assertEquals(750L, run("COMPLETED").runSeconds)
        assertEquals(750L, run("FAILED").runSeconds)
    }

    @Test
    fun `only COMPLETED and FAILED are finished`() {
        assertTrue(run("COMPLETED").finished)
        assertTrue(run("FAILED").finished)
        for (state in listOf("", "SUBMITTED", "RUNNING", "SUBMISSION_FAILED", "PENDING_RERUN", "SUCCEEDING", "FAILING", "SUSPENDED", "RESUMING", "UNKNOWN")) {
            assertFalse(run(state).finished, state)
        }
    }

    @Test
    fun `a running application has no run time, even with a termination time from an earlier attempt`() {
        assertNull(run("RUNNING").runSeconds)
        assertNull(run("SUBMITTED").runSeconds)
        assertNull(run("PENDING_RERUN").runSeconds)
    }

    @Test
    fun `a missing, unreadable or out-of-order time gives no run time`() {
        assertNull(run("COMPLETED", terminatedAt = "").runSeconds)
        assertNull(run("COMPLETED", submittedAt = "").runSeconds)
        assertNull(run("FAILED", terminatedAt = "yesterday").runSeconds)
        assertNull(run("COMPLETED", terminatedAt = "2026-01-01T09:59:59Z").runSeconds)
    }

    @Test
    fun `a termination at the submission second is a zero run`() {
        assertEquals(0L, run("FAILED", terminatedAt = "2026-01-01T10:00:00Z").runSeconds)
    }
}
