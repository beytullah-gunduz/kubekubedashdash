package com.kubekubedashdash.ui

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.TerminalSession
import com.kubekubedashdash.model.TerminalSessionId
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cmd/Ctrl+[ and ] walk the window's history on cluster and All Clusters tabs, and pass through
 * on a terminal tab (Ctrl+[ is Escape to the shell) and while an in-app modal is open.
 */
class HistoryShortcutRuleTest {
    private val session = ClusterSession()

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(session.scope, label = "HistoryShortcutRuleTest")
        session.connectionManager.close()
    }

    private fun terminalTab() = WorkspaceTab.Terminal(
        TerminalSession(
            id = TerminalSessionId.of(session.id.value, "default", "example-pod", "app"),
            clusterSession = session,
            podName = "example-pod",
            namespace = "default",
            container = "app",
        ),
    )

    @Test
    fun `the shortcut walks history on cluster and All Clusters tabs`() {
        assertTrue(historyShortcutApplies(WorkspaceTab.Cluster(session), modalOpen = false))
        assertTrue(historyShortcutApplies(WorkspaceTab.AllClusters, modalOpen = false))
    }

    @Test
    fun `a window with no active tab still takes the shortcut`() {
        assertTrue(historyShortcutApplies(null, modalOpen = false))
    }

    @Test
    fun `the shortcut passes through on a terminal tab`() {
        assertFalse(historyShortcutApplies(terminalTab(), modalOpen = false))
    }

    @Test
    fun `the shortcut passes through while a modal is open, on every tab kind`() {
        assertFalse(historyShortcutApplies(WorkspaceTab.Cluster(session), modalOpen = true))
        assertFalse(historyShortcutApplies(WorkspaceTab.AllClusters, modalOpen = true))
        assertFalse(historyShortcutApplies(terminalTab(), modalOpen = true))
        assertFalse(historyShortcutApplies(null, modalOpen = true))
    }
}
