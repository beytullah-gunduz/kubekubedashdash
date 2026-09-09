package com.kubekubedashdash.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Namespaces, Services and Deployments screens wire their Retry to the
 * client's own list flows (review follow-up F14). That button works only
 * because those three lists are built by the informer factory, which is
 * what [restartListFlow] restarts; a list derived from another flow — the
 * view models' `state`, or a future `combine` — would leave Retry a dead
 * click. This pins the precondition; the restart itself is proven at the
 * factory's seam. Nothing here connects: the client resolves lazily.
 */
class ReactiveKubeClientRestartableListsTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientRestartableListsTest", manager = manager)
    }

    @Test
    fun `the three lists the screens retry are factory-built and restart`() {
        assertTrue(restartListFlow(client.namespaces), "namespaces is a factory-built list")
        assertTrue(restartListFlow(client.services), "services is a factory-built list")
        assertTrue(restartListFlow(client.deployments), "deployments is a factory-built list")
    }
}
