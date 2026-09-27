package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coverage for the port-forward accessors added to [KubeConnectionManager]:
 * [KubeConnectionManager.isClosed], [KubeConnectionManager.connectedContextOrNull]
 * and [KubeConnectionManager.clientIfConnectedTo].
 *
 * The manager is always built with a `loadConfig` that throws — never
 * default-constructed — so [KubeConnectionManager.connect] never falls through to
 * `Config.autoConfigure`, which would read `$KUBECONFIG`.
 */
class KubeConnectionManagerPortForwardTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: KubernetesClient

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        client = server.createClient()
        manager = KubeConnectionManager(loadConfig = { throw IllegalStateException("offline") })
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "KubeConnectionManagerPortForwardTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `connectWithClient publishes the context and client pair`() {
        manager.connectWithClient(client, "ctx-a").getOrThrow()

        assertEquals("ctx-a", manager.connectedContextOrNull())
        assertSame(client, manager.clientIfConnectedTo("ctx-a"))
        assertNull(manager.clientIfConnectedTo("ctx-b"))
        assertFalse(manager.isClosed)
    }

    @Test
    fun `a failed connect leaves both accessors null but not closed`() {
        assertTrue(manager.connect("ctx-a").isFailure)

        assertNull(manager.connectedContextOrNull())
        assertNull(manager.clientIfConnectedTo("ctx-a"))
        assertFalse(manager.isClosed)
    }

    @Test
    fun `close nulls both accessors and sets isClosed`() {
        manager.connectWithClient(client, "ctx-a").getOrThrow()

        manager.close()

        assertNull(manager.connectedContextOrNull())
        assertNull(manager.clientIfConnectedTo("ctx-a"))
        assertTrue(manager.isClosed)
    }
}
