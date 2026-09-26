package com.kubekubedashdash.services.portforward

import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.ContainerPortInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ServiceInfo
import com.kubekubedashdash.models.ServicePortInfo
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.client.http.WebSocketHandshakeException
import io.fabric8.kubernetes.client.http.WebSocketUpgradeResponse
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortForwardTargetsTest {

    private fun pod(name: String, image: String = "app:1", ready: Boolean = true, restartCount: Int = 0, state: String = "Running", ports: List<ContainerPortInfo> = emptyList()) = ContainerInfo(name = name, image = image, ready = ready, restartCount = restartCount, state = state, ports = ports)

    private fun podInfo(name: String, namespace: String = "test", containers: List<ContainerInfo> = emptyList()) = PodInfo(
        uid = "uid-$name",
        name = name,
        namespace = namespace,
        status = "Running",
        ready = "1/1",
        restarts = 0,
        age = "1h",
        node = "node-a",
        ip = "10.0.0.1",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = containers,
    )

    private fun serviceInfo(name: String = "web-svc", portSpecs: List<ServicePortInfo> = emptyList()) = ServiceInfo(
        uid = "uid-$name",
        name = name,
        namespace = "test",
        type = "ClusterIP",
        clusterIP = "10.0.0.10",
        ports = "",
        age = "1h",
        selector = mapOf("app" to name),
        labels = emptyMap(),
        annotations = emptyMap(),
        portSpecs = portSpecs,
    )

    // ── defaultLocalPort ────────────────────────────────────────────────────

    @Test
    fun `defaultLocalPort maps privileged ports and leaves the rest alone`() {
        assertEquals(8080, defaultLocalPort(80))
        assertEquals(8443, defaultLocalPort(443))
        assertEquals(9023, defaultLocalPort(1023))
        assertEquals(1024, defaultLocalPort(1024))
        assertEquals(8080, defaultLocalPort(8080))
    }

    // ── isValidPort ─────────────────────────────────────────────────────────

    @Test
    fun `isValidPort accepts 1 to 65535 only`() {
        assertFalse(isValidPort(0))
        assertTrue(isValidPort(1))
        assertTrue(isValidPort(65535))
        assertFalse(isValidPort(65536))
    }

    // ── isPodReadyForForward ────────────────────────────────────────────────

    @Test
    fun `isPodReadyForForward is true when Running and Ready is True`() {
        val p = PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus().withPhase("Running")
            .addNewCondition().withType("Ready").withStatus("True").endCondition()
            .endStatus()
            .build()
        assertTrue(isPodReadyForForward(p))
    }

    @Test
    fun `isPodReadyForForward is false when Running and Ready is False`() {
        val p = PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus().withPhase("Running")
            .addNewCondition().withType("Ready").withStatus("False").endCondition()
            .endStatus()
            .build()
        assertFalse(isPodReadyForForward(p))
    }

    @Test
    fun `isPodReadyForForward falls back to container statuses when there is no Ready condition (demo shape)`() {
        val p = PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus().withPhase("Running")
            .withContainerStatuses(
                ContainerStatusBuilder().withName("c1").withReady(true).build(),
                ContainerStatusBuilder().withName("c2").withReady(true).build(),
            )
            .endStatus()
            .build()
        assertTrue(isPodReadyForForward(p))
    }

    @Test
    fun `isPodReadyForForward is false when there are no conditions and no container statuses`() {
        val p = PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus().withPhase("Running")
            .endStatus()
            .build()
        assertFalse(isPodReadyForForward(p))
    }

    @Test
    fun `isPodReadyForForward is false when Pending`() {
        val p = PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus().withPhase("Pending")
            .addNewCondition().withType("Ready").withStatus("True").endCondition()
            .endStatus()
            .build()
        assertFalse(isPodReadyForForward(p))
    }

    @Test
    fun `isPodReadyForForward is false when deletionTimestamp is set`() {
        val p = PodBuilder()
            .withNewMetadata().withName("p").withDeletionTimestamp("2026-01-01T00:00:00Z").endMetadata()
            .withNewStatus().withPhase("Running")
            .addNewCondition().withType("Ready").withStatus("True").endCondition()
            .endStatus()
            .build()
        assertFalse(isPodReadyForForward(p))
    }

    // ── pickServicePod ──────────────────────────────────────────────────────

    private fun readyPod(name: String, creationTimestamp: String, ready: Boolean = true) = PodBuilder()
        .withNewMetadata().withName(name).withCreationTimestamp(creationTimestamp).endMetadata()
        .withNewStatus().withPhase("Running")
        .addNewCondition().withType("Ready").withStatus(if (ready) "True" else "False").endCondition()
        .endStatus()
        .build()

    @Test
    fun `pickServicePod picks the oldest ready pod`() {
        val older = readyPod("web-0", "2026-01-01T00:00:00Z")
        val newer = readyPod("web-1", "2026-01-02T00:00:00Z")
        assertEquals("web-0", pickServicePod(listOf(newer, older))?.metadata?.name)
    }

    @Test
    fun `pickServicePod skips a not-ready older pod`() {
        val notReadyOlder = readyPod("web-0", "2026-01-01T00:00:00Z", ready = false)
        val readyNewer = readyPod("web-1", "2026-01-02T00:00:00Z")
        assertEquals("web-1", pickServicePod(listOf(notReadyOlder, readyNewer))?.metadata?.name)
    }

    @Test
    fun `pickServicePod tie-breaks equal timestamps by name`() {
        val b = readyPod("web-b", "2026-01-01T00:00:00Z")
        val a = readyPod("web-a", "2026-01-01T00:00:00Z")
        assertEquals("web-a", pickServicePod(listOf(b, a))?.metadata?.name)
    }

    @Test
    fun `pickServicePod returns null when nothing is ready`() {
        val p1 = readyPod("web-0", "2026-01-01T00:00:00Z", ready = false)
        val p2 = readyPod("web-1", "2026-01-02T00:00:00Z", ready = false)
        assertNull(pickServicePod(listOf(p1, p2)))
    }

    // ── resolveServiceTargetPort ────────────────────────────────────────────

    private fun podWithContainerPorts(vararg containers: Pair<String, List<Triple<String, Int, String>>>) = PodBuilder()
        .withNewMetadata().withName("p").endMetadata()
        .withNewSpec()
        .withContainers(
            containers.map { (containerName, ports) ->
                ContainerBuilder()
                    .withName(containerName)
                    .withPorts(
                        ports.map { (portName, port, protocol) ->
                            ContainerPortBuilder().withName(portName).withContainerPort(port).withProtocol(protocol).build()
                        },
                    )
                    .build()
            },
        )
        .endSpec()
        .build()

    @Test
    fun `resolveServiceTargetPort returns the int targetPort directly`() {
        val sp = ServicePortBuilder().withPort(80).withTargetPort(IntOrString(8080)).build()
        val pod = podWithContainerPorts("app" to emptyList())
        assertEquals(8080, resolveServiceTargetPort(sp, pod))
    }

    @Test
    fun `resolveServiceTargetPort resolves a named port on the second container`() {
        val sp = ServicePortBuilder().withPort(80).withTargetPort(IntOrString("http")).build()
        val pod = podWithContainerPorts(
            "sidecar" to listOf(Triple("metrics", 9090, "TCP")),
            "app" to listOf(Triple("http", 8080, "TCP")),
        )
        assertEquals(8080, resolveServiceTargetPort(sp, pod))
    }

    @Test
    fun `resolveServiceTargetPort returns null when the named port is not declared`() {
        val sp = ServicePortBuilder().withPort(80).withTargetPort(IntOrString("http")).build()
        val pod = podWithContainerPorts("app" to listOf(Triple("metrics", 9090, "TCP")))
        assertNull(resolveServiceTargetPort(sp, pod))
    }

    @Test
    fun `resolveServiceTargetPort falls back to the service port when targetPort is null`() {
        val sp = ServicePortBuilder().withPort(80).build()
        val pod = podWithContainerPorts("app" to emptyList())
        assertEquals(80, resolveServiceTargetPort(sp, pod))
    }

    @Test
    fun `resolveServiceTargetPort returns null when the named port exists only over UDP`() {
        val sp = ServicePortBuilder().withPort(80).withTargetPort(IntOrString("dns")).build()
        val pod = podWithContainerPorts("app" to listOf(Triple("dns", 53, "UDP")))
        assertNull(resolveServiceTargetPort(sp, pod))
    }

    // ── podPortOptions / servicePortOptions ─────────────────────────────────

    @Test
    fun `podPortOptions labels named and unnamed ports, excludes UDP, and collapses duplicates`() {
        val info = podInfo(
            "web-0",
            containers = listOf(
                pod(
                    "app",
                    ports = listOf(
                        ContainerPortInfo(name = "http", containerPort = 8080, protocol = "TCP"),
                        ContainerPortInfo(name = "", containerPort = 9090, protocol = "TCP"),
                        ContainerPortInfo(name = "dns", containerPort = 53, protocol = "UDP"),
                        ContainerPortInfo(name = "http", containerPort = 8080, protocol = "TCP"),
                    ),
                ),
            ),
        )
        val options = podPortOptions(info)
        assertEquals(
            listOf(
                RemotePortOption(8080, "8080 · http (app)"),
                RemotePortOption(9090, "9090 (app)"),
            ),
            options,
        )
    }

    @Test
    fun `servicePortOptions labels named and unnamed ports, excludes UDP, and collapses duplicates`() {
        val info = serviceInfo(
            portSpecs = listOf(
                ServicePortInfo(name = "http", port = 80, targetPort = "8080", protocol = "TCP"),
                ServicePortInfo(name = "", port = 443, targetPort = "", protocol = "TCP"),
                ServicePortInfo(name = "dns", port = 53, targetPort = "53", protocol = "UDP"),
                ServicePortInfo(name = "http", port = 80, targetPort = "8080", protocol = "TCP"),
            ),
        )
        val options = servicePortOptions(info)
        assertEquals(
            listOf(
                RemotePortOption(80, "80 · http → 8080"),
                RemotePortOption(443, "443"),
            ),
            options,
        )
    }

    // ── cleanForwardError ───────────────────────────────────────────────────

    @Test
    fun `cleanForwardError handles null, prefix stripping, refusal, multi-line and truncation`() {
        assertEquals("Connection failed", cleanForwardError(null))
        assertEquals(
            "Connection refused inside the pod — nothing is listening on that port",
            cleanForwardError("Received an error from the remote socket dial tcp4 127.0.0.1:80: connect: connection refused"),
        )
        assertEquals("first line", cleanForwardError("first line\nsecond line\nthird line"))
        val long400 = "x".repeat(400)
        assertEquals("x".repeat(299) + "…", cleanForwardError(long400))
    }

    // ── describeForwardFailure ──────────────────────────────────────────────

    @Test
    fun `describeForwardFailure maps handshake codes and unwraps CompletionException`() {
        assertEquals(
            "The pod no longer exists (HTTP 404)",
            describeForwardFailure(WebSocketHandshakeException(WebSocketUpgradeResponse(null, 404))),
        )
        assertEquals(
            "Permission denied by the API server (HTTP 403 — needs create on pods/portforward)",
            describeForwardFailure(WebSocketHandshakeException(WebSocketUpgradeResponse(null, 403))),
        )
        assertEquals(
            "API server refused the port-forward (HTTP 500)",
            describeForwardFailure(WebSocketHandshakeException(WebSocketUpgradeResponse(null, 500))),
        )
        assertEquals(
            "The pod no longer exists (HTTP 404)",
            describeForwardFailure(CompletionException(WebSocketHandshakeException(WebSocketUpgradeResponse(null, 404)))),
        )
        assertEquals("boom", describeForwardFailure(IllegalStateException("boom")))
    }
}
