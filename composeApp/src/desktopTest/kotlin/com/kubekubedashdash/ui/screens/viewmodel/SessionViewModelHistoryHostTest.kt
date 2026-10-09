package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a session reports to the window history it belongs to: each user
 * navigation (before it happens) and each fresh connect's context. A recording
 * fake stands in for the window, so no cluster is involved: the config loader
 * fails at once, which is enough to reach the report in `connectToCluster`.
 */
class SessionViewModelHistoryHostTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var viewModel: SessionViewModel
    private val host = RecordingHost()

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager(loadConfig = { error("offline: no cluster in this test") })
        viewModel = SessionViewModel(ReactiveKubeClient(scope, manager), scope)
        viewModel.historyHost = host
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "SessionViewModelHistoryHostTest", manager = manager)
    }

    @Test
    fun `navigate reports to the host before the screen changes`() {
        viewModel.navigate(Screen.Main.Nodes())
        viewModel.navigate(Screen.Main.Pods())

        // The host sees the OUTGOING screen each time.
        assertEquals(listOf("nav:" + Screen.Main.Connecting, "nav:" + Screen.Main.Nodes()), host.events)
    }

    @Test
    fun `a fresh connect reports the new context`() {
        viewModel.connectToCluster("example-ctx")

        assertEquals("ctx:example-ctx", host.events.last())
    }

    @Test
    fun `a reconnect does not report a context`() {
        viewModel.connectToCluster("example-ctx", isReconnect = true)

        assertTrue(host.events.none { it.startsWith("ctx:") })
    }
}

private class RecordingHost : NavigationHistoryHost {
    val events = mutableListOf<String>()

    override fun beforeNavigate(session: SessionViewModel) {
        events += "nav:" + session.currentScreen.value
    }

    override fun contextReplaced(session: SessionViewModel, context: String) {
        events += "ctx:$context"
    }
}
