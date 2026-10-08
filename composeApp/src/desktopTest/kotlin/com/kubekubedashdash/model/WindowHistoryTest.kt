package com.kubekubedashdash.model

import com.kubekubedashdash.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowHistoryTest {

    private fun at(tab: String, screen: Screen? = Screen.Main.Pods(), pane: Screen? = null) = HistoryLocation("cluster:$tab", "ctx-$tab", screen, pane)

    private val all: (HistoryLocation) -> Boolean = { true }

    @Test
    fun `a navigation records where the user was and clears Forward`() {
        val h = WindowHistory()
        h.recordNavigation(at("a"))
        assertEquals(listOf(at("a")), h.state.value.back)

        assertEquals(at("a"), h.back(at("a", Screen.Main.Nodes()), all))
        assertTrue(h.state.value.forward.isNotEmpty())

        h.recordNavigation(at("a", Screen.Main.Deployments()))
        assertTrue(h.state.value.forward.isEmpty())
    }

    @Test
    fun `leaving a connection screen records nothing but clears Forward`() {
        val h = WindowHistory()
        h.recordNavigation(at("a"))
        h.back(at("a", Screen.Main.Nodes()), all)
        assertTrue(h.state.value.forward.isNotEmpty())

        h.recordNavigation(null)
        assertTrue(h.state.value.back.isEmpty())
        assertTrue(h.state.value.forward.isEmpty())
    }

    @Test
    fun `back and forward move between the stacks`() {
        val h = WindowHistory()
        val a1 = at("a")
        val a2 = at("a", Screen.Main.Nodes())
        val a3 = at("a", Screen.Main.Deployments())
        h.recordNavigation(a1)
        h.recordNavigation(a2)

        assertEquals(a2, h.back(a3, all))
        assertEquals(listOf(a1), h.state.value.back)
        assertEquals(listOf(a3), h.state.value.forward)

        assertEquals(a3, h.forward(a2, all))
        assertEquals(listOf(a1, a2), h.state.value.back)
        assertTrue(h.state.value.forward.isEmpty())
    }

    @Test
    fun `back skips entries that are unusable or equal to where the user is`() {
        val h = WindowHistory()
        val a1 = at("a")
        val b1 = at("b")
        val c1 = at("c")
        h.recordNavigation(a1)
        h.recordNavigation(b1)
        h.recordNavigation(c1)

        assertEquals(a1, h.back(c1, usable = { it != b1 }))
        assertTrue(h.state.value.back.isEmpty())
    }

    @Test
    fun `back on an empty history is null and changes nothing`() {
        val h = WindowHistory()
        assertNull(h.back(at("a"), all))
        assertEquals(NavigationHistoryState(), h.state.value)
    }

    @Test
    fun `each stack keeps 50 entries`() {
        val h = WindowHistory()
        fun entry(i: Int) = at("a", Screen.Main.Pods(), Screen.Detail.ResourceDetail(kind = "Pod", name = "p$i", namespace = "ns-a"))
        for (i in 0 until 60) h.recordNavigation(entry(i))

        assertEquals(50, h.state.value.back.size)
        assertEquals(entry(10), h.state.value.back.first())
    }

    @Test
    fun `an entry equal to the newest one is not pushed twice`() {
        val h = WindowHistory()
        val a1 = at("a")
        h.recordNavigation(a1)
        h.recordNavigation(a1)
        assertEquals(listOf(a1), h.state.value.back)
    }

    @Test
    fun `a run of tab switches records only where it started`() {
        val h = WindowHistory()
        val a1 = at("a")
        val b1 = at("b")
        val c1 = at("c")
        h.beforeTabSwitch(a1)
        h.afterTabSwitch(b1)
        h.beforeTabSwitch(b1)
        h.afterTabSwitch(c1)
        assertEquals(listOf(a1), h.state.value.back)
    }

    @Test
    fun `a run that ends where it started leaves no entry`() {
        val h = WindowHistory()
        val a1 = at("a")
        val b1 = at("b")
        h.beforeTabSwitch(a1)
        h.afterTabSwitch(b1)
        h.beforeTabSwitch(b1)
        h.afterTabSwitch(a1)
        assertTrue(h.state.value.back.isEmpty())

        h.beforeTabSwitch(a1)
        h.afterTabSwitch(b1)
        assertEquals(listOf(a1), h.state.value.back)
    }

    @Test
    fun `a navigation ends the run`() {
        val h = WindowHistory()
        val a1 = at("a")
        val b1 = at("b")
        val b2 = at("b", Screen.Main.Nodes())
        val c1 = at("c")
        h.beforeTabSwitch(a1)
        h.afterTabSwitch(b1)
        h.recordNavigation(b1)
        h.beforeTabSwitch(b2)
        h.afterTabSwitch(c1)
        assertEquals(listOf(a1, b1, b2), h.state.value.back)
    }

    @Test
    fun `a tab switch clears Forward`() {
        val h = WindowHistory()
        val a1 = at("a")
        val a2 = at("a", Screen.Main.Nodes())
        h.recordNavigation(a1)
        h.back(a2, all)
        assertTrue(h.state.value.forward.isNotEmpty())

        h.beforeTabSwitch(a1)
        assertTrue(h.state.value.forward.isEmpty())
    }

    @Test
    fun `prune drops matching entries and collapses repeats`() {
        val h = WindowHistory()
        val a1 = at("a")
        val b1 = at("b")
        h.recordNavigation(a1)
        h.recordNavigation(b1)
        h.recordNavigation(a1)
        assertEquals(listOf(a1, b1, a1), h.state.value.back)

        h.prune { it.tabKey == "cluster:b" }
        assertEquals(listOf(a1), h.state.value.back)

        val h2 = WindowHistory()
        val bNodes = at("b", Screen.Main.Nodes())
        h2.recordNavigation(a1)
        h2.back(bNodes, all)
        assertEquals(listOf(bNodes), h2.state.value.forward)

        h2.prune { it.tabKey == "cluster:b" }
        assertTrue(h2.state.value.forward.isEmpty())
        assertTrue(h2.state.value.back.isEmpty())
    }

    @Test
    fun `clear forgets everything`() {
        val h = WindowHistory()
        h.recordNavigation(at("a"))
        h.back(at("a", Screen.Main.Nodes()), all)
        h.clear()
        assertEquals(NavigationHistoryState(), h.state.value)
    }

    @Test
    fun `pruning the run start ends the run`() {
        val h = WindowHistory()
        val a1 = at("a")
        val b1 = at("b")
        val c1 = at("c")
        h.beforeTabSwitch(a1)
        h.afterTabSwitch(b1)
        h.prune { it.tabKey == "cluster:a" }
        assertTrue(h.state.value.back.isEmpty())

        h.beforeTabSwitch(b1)
        h.afterTabSwitch(c1)
        assertEquals(listOf(b1), h.state.value.back)
    }
}
