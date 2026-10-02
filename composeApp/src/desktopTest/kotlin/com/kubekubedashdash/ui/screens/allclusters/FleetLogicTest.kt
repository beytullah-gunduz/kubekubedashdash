package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.ui.unit.dp
import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.PodPhaseCounts
import com.kubekubedashdash.ui.screens.allclusters.viewmodel.AllClustersViewModel.ClusterNodeUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules behind the All Clusters fleet strip, its warnings chips and the table's click-through. */
class FleetLogicTest {

    private fun node(sessionId: String, pressure: Float, name: String = "n-$sessionId-$pressure") = ClusterNodeUsage(SessionId(sessionId), "cluster-$sessionId", NodeResourceUsage(name, Math.round(pressure * 1000).toLong(), 1000, 0, 0))

    private fun event(sessionId: String?, uid: String = "u-1") = EventInfo(
        uid = uid,
        type = "Warning",
        reason = "BackOff",
        objectRef = "Pod/p0",
        message = "msg",
        count = 1,
        firstSeen = "1m",
        lastSeen = "1m",
        namespace = "ns-a",
        sessionId = sessionId,
    )

    @Test
    fun `fleetStripFits compares the minimum widths and gaps with the space`() {
        // 4 x 240 + 3 x 12 = 996
        assertTrue(fleetStripFits(996.dp, 4))
        assertFalse(fleetStripFits(995.dp, 4))
        assertTrue(fleetStripFits(240.dp, 1))
        assertTrue(fleetStripFits(0.dp, 0))
    }

    @Test
    fun `rankTopNodes orders across tabs, keeps each tab and honours the limit`() {
        val first = listOf(node("s-1", 0.2f), node("s-1", 0.9f))
        val second = listOf(node("s-2", 0.5f), node("s-2", 0.7f))

        val ranked = rankTopNodes(listOf(first, second))

        assertEquals(listOf(0.9f, 0.7f, 0.5f), ranked.map { it.usage.pressureFraction })
        assertEquals(listOf("s-1", "s-2", "s-2"), ranked.map { it.sessionId.value })
        assertEquals(listOf(0.9f, 0.7f), rankTopNodes(listOf(first, second), limit = 2).map { it.usage.pressureFraction })
    }

    @Test
    fun `warningsFilter narrows to warnings of one cluster and keeps the view settings`() {
        val current = EventTriageFilters(
            types = setOf("Normal"),
            clusters = setOf("x"),
            namespaces = setOf("ns-a"),
            reasons = setOf("r"),
            searchText = "q",
            timeWindow = TimeWindow.LAST_24H,
            mode = ViewMode.RAW,
            heatmapVisible = true,
        )

        val one = warningsFilter(current, "cluster-1")
        assertEquals(EventTriageFilters.DEFAULT_TYPES, one.types)
        assertEquals(setOf("cluster-1"), one.clusters)
        assertTrue(one.namespaces.isEmpty())
        assertTrue(one.reasons.isEmpty())
        assertEquals("", one.searchText)
        assertEquals(TimeWindow.LAST_24H, one.timeWindow)
        assertEquals(ViewMode.RAW, one.mode)
        assertTrue(one.heatmapVisible)

        assertTrue(warningsFilter(current, null).clusters.isEmpty())
    }

    @Test
    fun `warningsLabel says the window and agrees in number`() {
        assertEquals("No warnings in 1h", warningsLabel(0, TimeWindow.LAST_1H))
        assertEquals("1 warning in 1h", warningsLabel(1, TimeWindow.LAST_1H))
        assertEquals("23 warnings in 1h", warningsLabel(23, TimeWindow.LAST_1H))
    }

    @Test
    fun `phaseSummary names what is not running`() {
        assertEquals("—", phaseSummary(null))
        assertEquals("No pods", phaseSummary(PodPhaseCounts(0, 0, 0, 0)))
        assertEquals("None failed or pending", phaseSummary(PodPhaseCounts(10, 0, 0, 2)))
        assertEquals("14 failed · 4 pending", phaseSummary(PodPhaseCounts(10, 4, 14, 0)))
        assertEquals("4 pending", phaseSummary(PodPhaseCounts(10, 4, 0, 0)))
    }

    @Test
    fun `percentText follows the gauges rule`() {
        assertEquals("0%", percentText(0f))
        assertEquals("9.4%", percentText(0.094f))
        assertEquals("42%", percentText(0.42f))
        assertEquals("100%", percentText(1.5f))
    }

    @Test
    fun `typeFilterActive is false for the default and for all types`() {
        val available = setOf("Normal", "Warning", "Error")
        assertFalse(typeFilterActive(EventTriageFilters.DEFAULT_TYPES, available))
        assertFalse(typeFilterActive(available, available))
        assertFalse(typeFilterActive(emptySet(), available))
        assertTrue(typeFilterActive(setOf("Warning"), available))
        assertTrue(typeFilterActive(setOf("Normal"), available))
    }

    @Test
    fun `showsTrend needs two non-empty buckets`() {
        assertFalse(showsTrend(List(12) { 0 }))
        assertFalse(showsTrend(List(12) { if (it == 5) 3 else 0 }))
        assertTrue(showsTrend(List(12) { if (it == 5 || it == 9) 3 else 0 }))
    }

    @Test
    fun `eventJumpTarget needs a session and selects the event`() {
        assertNull(eventJumpTarget(event(sessionId = null)))
        assertNull(eventJumpTarget(event(sessionId = "")))
        assertEquals(
            SessionId("s-1") to Screen.Main.Events(selectEventUid = "u-1"),
            eventJumpTarget(event(sessionId = "s-1", uid = "u-1")),
        )
    }

    @Test
    fun `clusterCountsTooltip lists the largest cluster first`() {
        assertEquals("b: 3\na: 1", clusterCountsTooltip(mapOf("a" to 1, "b" to 3)))
    }

    @Test
    fun `a fresh filter is the default and starts on the default types`() {
        assertTrue(EventTriageFilters().isDefault)
        assertEquals(EventTriageFilters.DEFAULT_TYPES, EventTriageFilters().types)
    }
}
