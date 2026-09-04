package com.kubekubedashdash.ui

import com.kubekubedashdash.Screen
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterHealthSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.errorPodStatuses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins sidebarCounts' three signals: which key each maps to, its severity, its pre-filtered target, and its wording. */
class SidebarCountsTest {

    @Test
    fun `null health yields no counts`() {
        assertEquals(emptyMap(), sidebarCounts(null))
    }

    @Test
    fun `all-zero health yields no counts`() {
        val health = ClusterHealthSummary(podsInError = 0, nodesNotReady = 0, recentWarnings = 0)
        assertEquals(emptyMap(), sidebarCounts(health))
    }

    @Test
    fun `pods failing maps to the Pods key, ERROR severity and the banner's own target`() {
        val health = ClusterHealthSummary(podsInError = 3, nodesNotReady = 0, recentWarnings = 0)
        val count = sidebarCounts(health).getValue("Pods")
        assertEquals(3, count.value)
        assertEquals(CountSeverity.ERROR, count.severity)
        assertEquals(Screen.Main.Pods(statusFilter = errorPodStatuses()), count.target)
    }

    @Test
    fun `nodes not ready maps to the Nodes key, ERROR severity and the banner's own target`() {
        val health = ClusterHealthSummary(podsInError = 0, nodesNotReady = 2, recentWarnings = 0)
        val count = sidebarCounts(health).getValue("Nodes")
        assertEquals(2, count.value)
        assertEquals(CountSeverity.ERROR, count.severity)
        assertEquals(Screen.Main.Nodes(statusFilter = setOf("NotReady")), count.target)
    }

    @Test
    fun `recent warnings maps to the Events key, WARNING severity and the banner's own target`() {
        val health = ClusterHealthSummary(podsInError = 0, nodesNotReady = 0, recentWarnings = 5)
        val count = sidebarCounts(health).getValue("Events")
        assertEquals(5, count.value)
        assertEquals(CountSeverity.WARNING, count.severity)
        assertEquals(Screen.Main.Events(typeFilter = setOf("Warning", "Error")), count.target)
    }

    @Test
    fun `the events description names the warning window in minutes`() {
        val health = ClusterHealthSummary(podsInError = 0, nodesNotReady = 0, recentWarnings = 1)
        assertTrue(sidebarCounts(health).getValue("Events").description.contains("15 minutes"))
    }

    @Test
    fun `a count of one is worded in the singular`() {
        val health = ClusterHealthSummary(podsInError = 1, nodesNotReady = 1, recentWarnings = 1)
        val counts = sidebarCounts(health)
        assertEquals("1 pod failing — click to filter", counts.getValue("Pods").description)
        assertEquals("1 node not ready — click to filter", counts.getValue("Nodes").description)
        assertEquals("1 warning event in the last 15 minutes — click to filter", counts.getValue("Events").description)
    }
}
