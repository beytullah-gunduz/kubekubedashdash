package com.kubekubedashdash.services.session

import com.kubekubedashdash.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionSnapshotBuilderTest {

    private fun tab(context: String, namespace: String = "default", screen: Screen = Screen.Main.ClusterOverview, paneWidthDp: Float = 800f) = TabView(context = context, namespace = namespace, screen = screen, paneWidthDp = paneWidthDp)

    @Test
    fun `a blank-context tab is dropped and a window with only blank tabs is dropped`() {
        val mixed = WorkspaceView(
            tabs = listOf(tab(""), tab("example-context")),
            activeTabIndex = 1,
            geometry = null,
        )
        val onlyBlank = WorkspaceView(
            tabs = listOf(tab(""), tab("")),
            activeTabIndex = 0,
            geometry = null,
        )

        val snapshot = SessionSnapshotBuilder.build(listOf(mixed, onlyBlank))

        assertEquals(1, snapshot.workspaces.size)
        assertEquals(listOf("example-context"), snapshot.workspaces[0].tabs.map { it.context })
    }

    @Test
    fun `active index follows the kept tab or becomes -1 when dropped or unset`() {
        val active2 = WorkspaceView(
            tabs = listOf(tab(""), tab("example-context"), tab("example-cluster")),
            activeTabIndex = 2,
            geometry = null,
        )
        val activeOnDropped = WorkspaceView(
            tabs = listOf(tab(""), tab("example-context")),
            activeTabIndex = 0,
            geometry = null,
        )
        val activeAlreadyNone = WorkspaceView(
            tabs = listOf(tab("example-context")),
            activeTabIndex = -1,
            geometry = null,
        )

        val snapshot = SessionSnapshotBuilder.build(listOf(active2, activeOnDropped, activeAlreadyNone))

        assertEquals(1, snapshot.workspaces[0].activeTab)
        assertEquals(-1, snapshot.workspaces[1].activeTab)
        assertEquals(-1, snapshot.workspaces[2].activeTab)
    }

    @Test
    fun `screens are encoded, pane width and namespace pass through`() {
        val ws = WorkspaceView(
            tabs = listOf(
                tab(
                    context = "example-context",
                    namespace = "kube-system",
                    screen = Screen.Main.Pods(selectPodUid = "u"),
                    paneWidthDp = 650f,
                ),
            ),
            activeTabIndex = 0,
            geometry = null,
        )

        val snapshot = SessionSnapshotBuilder.build(listOf(ws))
        val savedTab = snapshot.workspaces[0].tabs[0]

        assertEquals("Pods", savedTab.screen.key)
        assertTrue(savedTab.screen.crd == null)
        assertEquals(650f, savedTab.paneWidthDp)
        assertEquals("kube-system", savedTab.namespace)
    }
}
