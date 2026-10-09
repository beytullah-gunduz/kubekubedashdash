package com.kubekubedashdash.model

import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tab dragged into a window — a merge, or the new window of a tear-out —
 * becomes that window's active tab, whatever its kind. A torn-out All Clusters
 * tab once arrived inactive in a window with nothing else, which then showed
 * the first-run screen instead of the fleet view.
 */
class WorkspaceAdoptTabTest {
    private val a = ClusterSession()
    private val b = ClusterSession()

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(a.scope, b.scope, label = "WorkspaceAdoptTabTest")
        a.connectionManager.close()
        b.connectionManager.close()
    }

    @Test
    fun `a torn-out All Clusters tab is active in its new window`() {
        val w = Workspace()

        w.adoptTab(WorkspaceTab.AllClusters)

        assertEquals(listOf<WorkspaceTab>(WorkspaceTab.AllClusters), w.tabs.value)
        assertEquals(WorkspaceTab.AllClusters.key, w.activeTabKey.value)
    }

    @Test
    fun `a merged All Clusters tab goes first and becomes active`() {
        val w = Workspace()
        w.addSession(a, makeActive = true)

        w.adoptTab(WorkspaceTab.AllClusters)

        assertEquals(WorkspaceTab.AllClusters, w.tabs.value.first())
        assertEquals(WorkspaceTab.AllClusters.key, w.activeTabKey.value)
    }

    @Test
    fun `a merged cluster tab becomes active`() {
        val w = Workspace()
        w.addSession(a, makeActive = true)

        w.adoptTab(WorkspaceTab.Cluster(b))

        assertEquals("cluster:${b.id.value}", w.activeTabKey.value)
    }
}
