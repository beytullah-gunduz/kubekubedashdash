package com.kubekubedashdash.k3s

import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.InClusterMetricsSource
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.SeriesResult
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import kotlinx.coroutines.delay
import org.testcontainers.DockerClientFactory
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.fail

/** One line of progress on stdout (Gradle shows it live). Never a URL, token, kubeconfig, query or response body. */
internal fun k3sSay(message: String) {
    println("[k3s] $message")
    System.out.flush()
}

/** Runs [block] and reports its name and duration; a failure is named before it propagates. */
internal inline fun <T> k3sStep(name: String, block: () -> T): T {
    val startedAt = System.nanoTime()
    try {
        val result = block()
        k3sSay("$name: ok (${(System.nanoTime() - startedAt) / 1_000_000_000}s)")
        return result
    } catch (e: Throwable) {
        k3sSay("$name: FAILED (${e.javaClass.simpleName}, ${(System.nanoTime() - startedAt) / 1_000_000_000}s)")
        throw e
    }
}

/**
 * A throwaway K3s cluster (Testcontainers) with `monitoring/prometheus-server` scraping the node's
 * cAdvisor and the pod `team-a/web-0`, plus two clients: [admin], and [viewer], a service account
 * that holds only the built-in `view` role in `team-a`.
 *
 * Every client is built from the cluster's own credentials with auto-configuration off, so no
 * kubeconfig of the developer is ever read. The credentials stay inside this object, which has no
 * `toString`, and in the three files [writeKubeconfigs] writes (owner-only, throwaway cluster).
 */
internal class K3sCluster private constructor(
    private val container: K3sContainer,
    private val credentials: Credentials,
    val admin: KubernetesClient,
    val viewer: KubernetesClient,
) : AutoCloseable {
    private class Credentials(
        val masterUrl: String,
        val caCertData: String,
        val clientCertData: String,
        val clientKeyData: String,
        val viewerToken: String,
    )

    /**
     * Writes `kubeconfig-admin.yaml`, `kubeconfig-viewer.yaml` and `kubeconfig-viewer-ns.yaml` into
     * [dir], readable by the owner only, and returns them in that order. The last is the viewer's
     * file with the workload namespace as its context's `namespace:`. Each one is read back and
     * used once, so a file that does not work fails here and not in the app.
     */
    fun writeKubeconfigs(dir: File): List<File> {
        dir.mkdirs()
        val adminFile = writeOwnerOnly(
            File(dir, "kubeconfig-admin.yaml"),
            kubeconfigYaml(
                "k3s-admin",
                listOf("client-certificate-data: \"${credentials.clientCertData}\"", "client-key-data: \"${credentials.clientKeyData}\""),
            ),
        )
        val viewerFile = writeOwnerOnly(
            File(dir, "kubeconfig-viewer.yaml"),
            kubeconfigYaml("k3s-viewer", listOf("token: \"${credentials.viewerToken}\"")),
        )
        val viewerNamespaceFile = writeOwnerOnly(
            File(dir, "kubeconfig-viewer-ns.yaml"),
            kubeconfigYaml("k3s-viewer-ns", listOf("token: \"${credentials.viewerToken}\""), namespace = WORKLOAD_NAMESPACE),
        )
        val files = listOf(adminFile, viewerFile, viewerNamespaceFile)
        for (file in files) {
            clientFromFile(file).use { client ->
                check(client.pods().inNamespace(WORKLOAD_NAMESPACE).withName(WORKLOAD_POD).get() != null) { "${file.name} cannot read the workload pod" }
            }
        }
        return files
    }

    private fun kubeconfigYaml(name: String, userLines: List<String>, namespace: String? = null): String = buildString {
        appendLine("apiVersion: v1")
        appendLine("kind: Config")
        appendLine("clusters:")
        appendLine("  - name: $name")
        appendLine("    cluster:")
        appendLine("      server: \"${credentials.masterUrl}\"")
        appendLine("      certificate-authority-data: \"${credentials.caCertData}\"")
        appendLine("users:")
        appendLine("  - name: $name")
        appendLine("    user:")
        userLines.forEach { appendLine("      $it") }
        appendLine("contexts:")
        appendLine("  - name: $name")
        appendLine("    context:")
        appendLine("      cluster: $name")
        appendLine("      user: $name")
        if (namespace != null) appendLine("      namespace: $namespace")
        appendLine("current-context: $name")
    }

    private fun writeOwnerOnly(file: File, text: String): File {
        val path = file.toPath()
        Files.deleteIfExists(path)
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(path, text)
        return file
    }

    /**
     * Polls until the workload's cAdvisor series are in Prometheus and every history kind the pod
     * panel draws has data through the real [InClusterMetricsSource]: CPU and memory for both
     * containers, throttling for `app` only, and both network directions. A rate needs two samples
     * in its window, so this takes a minute or two after the pods are ready.
     */
    suspend fun awaitWorkloadMetrics() {
        val source = InClusterMetricsSource(
            target = PROMETHEUS_TARGET,
            auto = false,
            clientProvider = { admin },
            detection = InClusterDetection(),
            key = "k3s-wait",
            accessReview = { null },
        )
        val pod = PodRef(WORKLOAD_NAMESPACE, WORKLOAD_POD)
        var missing = listOf("everything")
        try {
            pollUntil("the workload's metrics in Prometheus", timeoutSec = DATA_TIMEOUT_SEC) {
                val window = HistoryRange.M15.window(Instant.now().epochSecond)
                missing = PodSeriesKind.entries.mapNotNull { kind ->
                    val names = (source.podSeries(kind, pod, window) as? SeriesResult.Ok)?.series?.map { it.name }.orEmpty()
                    val complete = when (kind) {
                        PodSeriesKind.Cpu, PodSeriesKind.Memory -> names.containsAll(CONTAINERS)
                        PodSeriesKind.Throttling -> "app" in names
                        PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx -> names.isNotEmpty()
                    }
                    kind.name.takeUnless { complete }
                }
                missing.isEmpty()
            }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; still missing: ${missing.joinToString()}", e)
        }
    }

    override fun close() {
        // Every close gets its turn even if an earlier one throws.
        val failures = listOf(admin::close, viewer::close, container::stop).mapNotNull { runCatching { it() }.exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    companion object {
        /** Pinned: rancher/k3s v1.36.4-k3s1 (published 2026-08-27, linux/amd64, arm and arm64). */
        const val IMAGE = "rancher/k3s:v1.36.4-k3s1"
        const val WORKLOAD_NAMESPACE = "team-a"
        const val WORKLOAD_POD = "web-0"

        /** `prometheus.yaml`'s Service: detection rank 2, port name `http` on port 80 (container port 9090). */
        val PROMETHEUS_TARGET = InClusterTarget("monitoring", "prometheus-server", "http", https = false, pathPrefix = "")

        private val CONTAINERS = listOf("app", "sidecar")
        private const val START_TIMEOUT_MIN = 3L
        private const val READY_TIMEOUT_MIN = 6L
        private const val DATA_TIMEOUT_SEC = 300L
        private const val APPLY_ATTEMPTS = 10
        private const val VIEWER_TOKEN_SEC = 7_200L

        /**
         * Fails at once, naming the settings a Colima user needs, when no Docker daemon answers.
         * Testcontainers reads them from the environment of the Gradle call; nothing here prints a value.
         */
        fun requireDocker() {
            val available = try {
                DockerClientFactory.instance().isDockerAvailable()
            } catch (e: Exception) {
                false
            }
            if (!available) {
                fail(
                    "Docker is not reachable, so the K3s test cannot start. With Colima, export DOCKER_HOST and " +
                        "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE (and, if needed, TESTCONTAINERS_HOST_OVERRIDE) before " +
                        "running Gradle. The values are in README.md, section \"Integration tests (K3s)\". " +
                        "This test never skips silently.",
                )
            }
        }

        fun start(): K3sCluster {
            requireDocker()
            val container = K3sContainer(DockerImageName.parse(IMAGE)).withStartupTimeout(Duration.ofMinutes(START_TIMEOUT_MIN))
            try {
                k3sStep("start K3s (the first run pulls the image)") { container.start() }
                // Only to read the fields: fromKubeconfig(String) starts from an empty Config, with no auto-configuration.
                val fromContainer = Config.fromKubeconfig(container.kubeConfigYaml)
                val masterUrl = requireField(fromContainer.masterUrl)
                val caCertData = requireField(fromContainer.caCertData)
                val clientCertData = requireField(fromContainer.clientCertData)
                val clientKeyData = requireField(fromContainer.clientKeyData)
                // K3s issues EC client keys; a Config built field by field defaults to RSA, which cannot read them.
                val clientKeyAlgo = Config.getKeyAlgorithm(null, clientKeyData)
                val admin = newClient(
                    ConfigBuilder().withAutoConfigure(false).withMasterUrl(masterUrl).withCaCertData(caCertData)
                        .withClientCertData(clientCertData).withClientKeyData(clientKeyData).withClientKeyAlgo(clientKeyAlgo).build(),
                )
                var viewer: KubernetesClient? = null
                try {
                    k3sStep("apply prometheus.yaml") { applyManifest(admin, "prometheus.yaml") }
                    k3sStep("apply workload.yaml") { applyManifest(admin, "workload.yaml") }
                    k3sStep("wait for Prometheus and web-0 to be Ready (the first run pulls their images)") { awaitReady(admin) }
                    val token = k3sStep("request the viewer's token") { viewerToken(admin) }
                    viewer = newClient(
                        ConfigBuilder().withAutoConfigure(false).withMasterUrl(masterUrl).withCaCertData(caCertData).withOauthToken(token).build(),
                    )
                    return K3sCluster(container, Credentials(masterUrl, caCertData, clientCertData, clientKeyData, token), admin, viewer)
                } catch (e: Throwable) {
                    runCatching { viewer?.close() }
                    runCatching { admin.close() }
                    throw e
                }
            } catch (e: Throwable) {
                runCatching { container.stop() }
                throw e
            }
        }

        // No request retries: a failing call should show itself, and the app's own retry budget is not under test.
        private fun newClient(config: Config): KubernetesClient = KubernetesClientBuilder()
            .withConfig(ConfigBuilder(config).withAutoConfigure(false).withRequestRetryBackoffLimit(0).build())
            .build()

        private fun clientFromFile(file: File): KubernetesClient = newClient(Config.fromKubeconfig(file))

        private fun requireField(value: String?): String {
            check(!value.isNullOrBlank()) { "the K3s kubeconfig lacks a field this test needs" }
            return value
        }

        // Server-side apply is idempotent, so a retry repeats nothing. The first attempts can be refused
        // because the namespace's default service account does not exist yet.
        private fun applyManifest(client: KubernetesClient, resource: String) {
            var last: KubernetesClientException? = null
            repeat(APPLY_ATTEMPTS) { attempt ->
                try {
                    val stream = K3sCluster::class.java.getResourceAsStream("/k3s/$resource") ?: fail("the manifest /k3s/$resource is not on the test classpath")
                    stream.use { client.load(it).serverSideApply() }
                    return
                } catch (e: KubernetesClientException) {
                    last = e
                    if (attempt < APPLY_ATTEMPTS - 1) Thread.sleep(3_000)
                }
            }
            fail("applying $resource failed $APPLY_ATTEMPTS times, last HTTP status ${last?.code}")
        }

        private fun awaitReady(client: KubernetesClient) {
            try {
                client.apps().deployments().inNamespace("monitoring").withName("prometheus")
                    .waitUntilCondition({ deployment -> (deployment?.status?.readyReplicas ?: 0) >= 1 }, READY_TIMEOUT_MIN, TimeUnit.MINUTES)
                client.pods().inNamespace(WORKLOAD_NAMESPACE).withName(WORKLOAD_POD).waitUntilReady(READY_TIMEOUT_MIN, TimeUnit.MINUTES)
            } catch (e: Exception) {
                k3sSay("pods that were not ready: ${describePods(client)}")
                throw e
            }
        }

        // Phase and the reason a container is waiting (ImagePullBackOff, ...), for a start that did not finish.
        private fun describePods(client: KubernetesClient): String = try {
            client.pods().inAnyNamespace().list().items.orEmpty().filter { it.metadata?.namespace in listOf("monitoring", WORKLOAD_NAMESPACE) }
                .joinToString("; ") { pod -> "${pod.metadata?.name}=${phaseOf(pod)}" }
        } catch (e: Exception) {
            "unavailable (${e.javaClass.simpleName})"
        }

        private fun phaseOf(pod: Pod): String {
            val waiting = pod.status?.containerStatuses.orEmpty().mapNotNull { it.state?.waiting?.reason }
            return (pod.status?.phase ?: "?") + if (waiting.isEmpty()) "" else waiting.joinToString(",", "(", ")")
        }

        private fun viewerToken(admin: KubernetesClient): String {
            val request = TokenRequestBuilder().withNewSpec().withExpirationSeconds(VIEWER_TOKEN_SEC).endSpec().build()
            val issued = admin.serviceAccounts().inNamespace(WORKLOAD_NAMESPACE).withName("viewer").tokenRequest(request)
            return requireField(issued?.status?.token)
        }
    }
}

/**
 * Calls [attempt] every [intervalSec] until it returns true. An attempt that throws counts as "not yet"
 * (the Service has no endpoints for a moment, a request times out); the last failure's class is in the message.
 */
internal suspend fun pollUntil(what: String, timeoutSec: Long, intervalSec: Long = 5, attempt: suspend () -> Boolean) {
    val deadline = System.nanoTime() + timeoutSec * 1_000_000_000
    var last = "not yet"
    while (true) {
        try {
            if (attempt()) return
            last = "not yet"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e.javaClass.simpleName
        }
        if (System.nanoTime() >= deadline) fail("timed out after ${timeoutSec}s waiting for $what (last attempt: $last)")
        delay(intervalSec * 1_000)
    }
}
