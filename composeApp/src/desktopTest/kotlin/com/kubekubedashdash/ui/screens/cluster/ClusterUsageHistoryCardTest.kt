package com.kubekubedashdash.ui.screens.cluster

import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.ScopeKind
import com.kubekubedashdash.services.metricshistory.ScopeRef
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure decisions behind [ClusterUsageHistoryCard]: scope, refresh rule, headline failure and note. */
class ClusterUsageHistoryCardTest {

    private val window = QueryWindow(0, 21_600, 180)
    private val ok = SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(0, 1.0)))))
    private val empty = SeriesResult.Ok(emptyList())
    private val pointless = SeriesResult.Ok(listOf(Series("", emptyList())))
    private val cpuFailed = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")
    private val memoryFailed = SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403")

    private fun loaded(cpu: SeriesResult, memory: SeriesResult) = LoadedScope(window, mapOf(ScopeKind.CpuCores to cpu, ScopeKind.MemoryBytes to memory))

    @Test
    fun `all namespaces sum over every namespace`() {
        assertEquals(ScopeRef(emptySet()), scopeRefOf(NamespaceScope.All))
    }

    @Test
    fun `a selection sums over exactly those namespaces`() {
        assertEquals(ScopeRef(setOf("team-a")), scopeRefOf(NamespaceScope.single("team-a")))
        assertEquals(ScopeRef(setOf("team-a", "team-b")), scopeRefOf(NamespaceScope.of(listOf("team-b", "team-a"))))
    }

    @Test
    fun `the first fetch and a moved window ask`() {
        assertTrue(needsScopeFetch(null, window))
        assertTrue(needsScopeFetch(loaded(ok, ok), QueryWindow(180, 21_780, 180)))
    }

    @Test
    fun `an identical, fully answered window asks nothing`() {
        assertFalse(needsScopeFetch(loaded(ok, ok), window))
        // One kind with points is enough to hold off the next round, as long as both answered.
        assertFalse(needsScopeFetch(loaded(ok, empty), window))
    }

    @Test
    fun `a failure or an answer without a point is asked again`() {
        assertTrue(needsScopeFetch(loaded(ok, memoryFailed), window))
        assertTrue(needsScopeFetch(loaded(cpuFailed, cpuFailed), window))
        assertTrue(needsScopeFetch(loaded(empty, empty), window))
        assertTrue(needsScopeFetch(loaded(pointless, pointless), window))
    }

    @Test
    fun `hasPoints is true when either kind has a point`() {
        assertTrue(loaded(ok, empty).hasPoints)
        assertTrue(loaded(cpuFailed, ok).hasPoints)
        assertFalse(loaded(empty, pointless).hasPoints)
        assertFalse(loaded(cpuFailed, memoryFailed).hasPoints)
    }

    @Test
    fun `the headline failure is CPU's, else memory's`() {
        assertEquals(cpuFailed, scopeHeadlineFailure(loaded(cpuFailed, memoryFailed)))
        assertEquals(memoryFailed, scopeHeadlineFailure(loaded(empty, memoryFailed)))
        assertEquals(cpuFailed, scopeHeadlineFailure(loaded(cpuFailed, empty)))
    }

    @Test
    fun `there is no headline failure while something can be drawn or nothing failed`() {
        assertNull(scopeHeadlineFailure(loaded(cpuFailed, ok)))
        assertNull(scopeHeadlineFailure(loaded(ok, memoryFailed)))
        assertNull(scopeHeadlineFailure(loaded(empty, empty)))
    }

    @Test
    fun `the note names the source and says CPU lags the gauge`() {
        assertEquals(
            "From Prometheus · monitoring/prometheus-server. CPU is a trailing average over several minutes, " +
                "so it lags the live gauge and can differ from it.",
            usageHistoryNote("Prometheus · monitoring/prometheus-server"),
        )
    }
}
