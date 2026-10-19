package com.kubekubedashdash.services.metricshistory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureClassificationTest {

    @Test
    fun `only Unavailable can be transient, whatever the detail`() {
        HistoryFailure.entries.filter { it != HistoryFailure.Unavailable }.forEach { failure ->
            listOf("", "timeout", "HTTP 503", "error").forEach { detail ->
                assertFalse(SeriesResult.Failed(failure, detail).isTransient(), "$failure ($detail) is structural")
            }
        }
    }

    @Test
    fun `an Unavailable answer is transient except an unsupported query or a bad request`() {
        assertTrue(SeriesResult.Failed(HistoryFailure.Unavailable, "error").isTransient())
        assertTrue(SeriesResult.Failed(HistoryFailure.Unavailable, "timeout").isTransient())
        assertTrue(SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503").isTransient())
        assertTrue(SeriesResult.Failed(HistoryFailure.Unavailable).isTransient())
        assertFalse(SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported").isTransient())
        assertFalse(SeriesResult.Failed(HistoryFailure.Unavailable, "bad request").isTransient())
    }

    @Test
    fun `the failures the plan names are classified as it says`() {
        val structural = setOf(
            HistoryFailure.GcloudUnavailable,
            HistoryFailure.NotSignedIn,
            HistoryFailure.Forbidden,
            HistoryFailure.NoContainerMetrics,
            HistoryFailure.BadQuery,
            HistoryFailure.NoProxyAccess,
            HistoryFailure.ServiceGone,
            HistoryFailure.WrongPath,
        )
        assertEquals(HistoryFailure.entries.toSet() - HistoryFailure.Unavailable, structural, "a new HistoryFailure needs a classification here")
    }
}
