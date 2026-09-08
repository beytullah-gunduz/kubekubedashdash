package com.kubekubedashdash.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.http.Dispatcher
import io.fabric8.mockwebserver.http.MockResponse
import io.fabric8.mockwebserver.http.RecordedRequest
import org.slf4j.LoggerFactory
import java.io.InterruptedIOException
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KubeClient` keeps its forgiving `get*` methods for the GUI (a string on
 * every outcome) and gains throwing `fetch*` twins for MCP, which must report
 * failures as errors (review follow-up F9). An interrupted call — the shape a
 * cancelled `runInterruptible` leaves behind — is logged at DEBUG by the
 * forgiving methods, not ERROR. Loopback mock only; no real user state.
 */
class KubeClientFetchTest {

    private class ForbiddenLogs(private val crud: KubernetesCrudDispatcher) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.substringBefore('?')
            if (path.endsWith("/log")) {
                return MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody(
                    """{"kind":"Status","apiVersion":"v1","status":"Failure","message":"pods \"p\" is forbidden: no access to logs","reason":"Forbidden","code":403}""",
                )
            }
            return crud.dispatch(request)
        }
    }

    private lateinit var mock: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var seed: KubernetesClient
    private lateinit var client: KubeClient

    private val logger = LoggerFactory.getLogger(KubeClient::class.java) as Logger
    private val events = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeTest
    fun setUp() {
        mock = KubernetesMockServer(Context(), MockWebServer(), HashMap(), ForbiddenLogs(KubernetesCrudDispatcher()), false)
        mock.init()
        seed = mock.createClient()
        manager = KubeConnectionManager()
        manager.connectWithClient(mock.createClient(), "cluster-a").getOrThrow()
        client = KubeClient(manager)
        logger.addAppender(events)
    }

    @AfterTest
    fun tearDown() {
        logger.detachAppender(events)
        events.stop()
        shutdownCleanly(label = "KubeClientFetchTest", manager = manager, client = seed, servers = listOf(mock))
    }

    @Test
    fun `fetchPodLogs throws on a forbidden pod while getPodLogs keeps returning the error string`() {
        val thrown = assertFailsWith<KubernetesClientException> { client.fetchPodLogs("p", "ns", null, 10) }
        assertEquals(403, thrown.code)

        val forgiving = client.getPodLogs("p", "ns", null, 10)
        assertTrue(forgiving.startsWith("Error fetching logs: "), "the GUI contract is unchanged, got: $forgiving")
        assertTrue(events.list.any { it.level == Level.ERROR }, "a real failure is still logged at ERROR")
    }

    @Test
    fun `fetchResourceYaml is null for a missing resource while getResourceYaml keeps its comment`() {
        assertNull(client.fetchResourceYaml("pod", "ghost", "ns"))
        assertEquals("# Resource not found", client.getResourceYaml("pod", "ghost", "ns"))
    }

    @Test
    fun `an interrupted fetch is logged at debug, not error, and the forgiving string is unchanged`() {
        val laundered = KubernetesClientException("An error has occurred.", InterruptedIOException("interrupted").initCause(InterruptedException()))
        val throwing = Proxy.newProxyInstance(KubernetesClient::class.java.classLoader, arrayOf(KubernetesClient::class.java)) { self, method, args ->
            when (method.name) {
                "toString" -> "ThrowingKubernetesClient"
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "close" -> null
                else -> throw laundered
            }
        } as KubernetesClient
        val interruptedManager = KubeConnectionManager()
        try {
            interruptedManager.connectWithClient(throwing, "cluster-b").getOrThrow()
            val interrupted = KubeClient(interruptedManager)

            assertEquals("Error fetching logs: An error has occurred.", interrupted.getPodLogs("p", "ns", null, 10))
            assertEquals("# Error: An error has occurred.", interrupted.getResourceYaml("pod", "p", "ns"))
        } finally {
            interruptedManager.close()
        }

        assertFalse(events.list.any { it.level == Level.ERROR }, "an interruption is not an error: ${events.list.map { it.level to it.formattedMessage }}")
        assertEquals(2, events.list.count { it.level == Level.DEBUG && it.formattedMessage.contains("interrupted") }, "one DEBUG line per interrupted fetch, got: ${events.list.map { it.level to it.formattedMessage }}")
    }

    @Test
    fun `isInterruption finds the interruption anywhere in the cause chain and nowhere else`() {
        assertTrue(isInterruption(InterruptedException()))
        assertTrue(isInterruption(KubernetesClientException("x", InterruptedIOException("y").initCause(InterruptedException()))))
        assertTrue(isInterruption(IllegalStateException("wrapped", InterruptedException())))
        assertFalse(isInterruption(KubernetesClientException("x", java.io.IOException("connection refused"))))
        assertFalse(isInterruption(IllegalStateException("plain")))
    }
}
