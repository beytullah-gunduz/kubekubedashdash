package com.kubekubedashdash.services

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which window a request for the All Clusters tab lands in. Builds real
 * [Workspace]s and (unconnected) [ClusterSession]s but never touches
 * [WorkspaceManager], a process-wide singleton that opens a workspace on first use.
 */
class AllClustersHostTest {
    private val sessions = mutableListOf<ClusterSession>()

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @AfterTest
    fun closeSessions() {
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    /** A workspace holding [clusterTabs] unconnected cluster tabs. */
    private fun workspace(clusterTabs: Int): Workspace {
        val workspace = Workspace()
        repeat(clusterTabs) {
            val session = ClusterSession().also(sessions::add)
            workspace.addSession(session, makeActive = true)
        }
        return workspace
    }

    @Test
    fun `one cluster tab in total has nothing to compare`() {
        val a = workspace(clusterTabs = 1)

        assertNull(allClustersHost(listOf(a), a))
    }

    @Test
    fun `two windows with one cluster tab each land in the requesting window`() {
        val a = workspace(clusterTabs = 1)
        val b = workspace(clusterTabs = 1)

        assertSame(b, allClustersHost(listOf(a, b), b))
    }

    @Test
    fun `two cluster tabs in one window land in that window`() {
        val a = workspace(clusterTabs = 2)

        assertSame(a, allClustersHost(listOf(a), a))
    }

    @Test
    fun `an existing All Clusters tab pulls the request to its window`() {
        val a = workspace(clusterTabs = 1)
        a.ensureAllClustersTabAt(0)
        val b = workspace(clusterTabs = 1)

        assertSame(a, allClustersHost(listOf(a, b), b))
    }

    @Test
    fun `an All Clusters tab alone does not count as two clusters`() {
        val a = workspace(clusterTabs = 1)
        a.ensureAllClustersTabAt(0)

        assertNull(allClustersHost(listOf(a), a))
    }
}
