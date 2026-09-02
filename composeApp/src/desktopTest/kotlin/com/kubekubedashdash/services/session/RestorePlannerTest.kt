package com.kubekubedashdash.services.session

import com.kubekubedashdash.model.ScreenBounds
import com.kubekubedashdash.model.WindowGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestorePlannerTest {

    @Test
    fun `null snapshot yields an empty plan`() {
        assertTrue(RestorePlanner.plan(null, availableContexts = emptyList(), screens = emptyList()).isEmpty())
    }

    @Test
    fun `an unavailable context is dropped, a demo context is kept, an emptied window is dropped`() {
        val snapshot = SessionSnapshot(
            workspaces = listOf(
                SavedWorkspace(
                    tabs = listOf(
                        SavedClusterTab(context = "example-context"),
                        SavedClusterTab(context = "demo-cluster (mock) #2"),
                    ),
                    activeTab = 0,
                ),
                SavedWorkspace(
                    tabs = listOf(SavedClusterTab(context = "gone-context")),
                    activeTab = 0,
                ),
            ),
        )

        val plan = RestorePlanner.plan(snapshot, availableContexts = listOf("example-context"), screens = emptyList())

        assertEquals(1, plan.size)
        assertEquals(listOf("example-context", "demo-cluster (mock) #2"), plan[0].tabs.map { it.context })
    }

    @Test
    fun `active tab remaps after a drop and falls back to 0 when dropped or unset`() {
        val remapped = SavedWorkspace(
            tabs = listOf(
                SavedClusterTab(context = "gone-context"),
                SavedClusterTab(context = "example-context"),
                SavedClusterTab(context = "example-cluster"),
            ),
            activeTab = 2,
        )
        val activeWasDropped = SavedWorkspace(
            tabs = listOf(
                SavedClusterTab(context = "gone-context"),
                SavedClusterTab(context = "example-context"),
            ),
            activeTab = 0,
        )
        val activeWasUnset = SavedWorkspace(
            tabs = listOf(SavedClusterTab(context = "example-context")),
            activeTab = -1,
        )

        val plan = RestorePlanner.plan(
            SessionSnapshot(workspaces = listOf(remapped, activeWasDropped, activeWasUnset)),
            availableContexts = listOf("example-context", "example-cluster"),
            screens = emptyList(),
        )

        assertEquals(1, plan[0].activeTab)
        assertEquals(0, plan[1].activeTab)
        assertEquals(0, plan[2].activeTab)
    }

    @Test
    fun `pane width is clamped and a blank namespace becomes All Namespaces`() {
        val snapshot = SessionSnapshot(
            workspaces = listOf(
                SavedWorkspace(
                    tabs = listOf(
                        SavedClusterTab(context = "example-context", namespace = "", paneWidthDp = 5000f),
                        SavedClusterTab(context = "example-cluster", namespace = "kube-system", paneWidthDp = 10f),
                    ),
                    activeTab = 0,
                ),
            ),
        )

        val plan = RestorePlanner.plan(
            snapshot,
            availableContexts = listOf("example-context", "example-cluster"),
            screens = emptyList(),
        )

        val tabs = plan[0].tabs
        assertEquals(1200f, tabs[0].paneWidthDp)
        assertEquals("All Namespaces", tabs[0].namespace)
        assertEquals(400f, tabs[1].paneWidthDp)
    }

    @Test
    fun `off-screen geometry drops position but keeps size, empty screens leaves it unchanged`() {
        val geometry = WindowGeometry(x = 5000, y = 5000, widthDp = 1440, heightDp = 960)
        val snapshot = SessionSnapshot(
            workspaces = listOf(
                SavedWorkspace(
                    tabs = listOf(SavedClusterTab(context = "example-context")),
                    activeTab = 0,
                    geometry = geometry,
                ),
            ),
        )

        val offScreenPlan = RestorePlanner.plan(
            snapshot,
            availableContexts = listOf("example-context"),
            screens = listOf(ScreenBounds(0, 0, 1920, 1080)),
        )
        val restoredGeometry = offScreenPlan[0].geometry
        assertNull(restoredGeometry?.x)
        assertNull(restoredGeometry?.y)
        assertEquals(1440, restoredGeometry?.widthDp)
        assertEquals(960, restoredGeometry?.heightDp)

        val unknownScreensPlan = RestorePlanner.plan(
            snapshot,
            availableContexts = listOf("example-context"),
            screens = emptyList(),
        )
        assertEquals(geometry, unknownScreensPlan[0].geometry)
    }
}
