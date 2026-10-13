package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Queue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the quota Usage tab reads from the cluster: one row per hard key of a ResourceQuota, with the used value
 * and the used/hard fraction. The rows come from `status.hard` once the quota controller has filled it in, and
 * from `spec.hard` before that. Loopback mock only: the CRUD store serves the quotas.
 */
class ReactiveKubeClientQuotaUsageTest {

    private lateinit var responses: MutableMap<ServerRequest, Queue<ServerResponse>>
    private lateinit var server: KubernetesMockServer
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient

    @BeforeTest
    fun setUp() {
        responses = HashMap()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()

        // No request retries: fabric8 retries a 5xx for ~100 s by default.
        val config = server.createClient().use { ConfigBuilder(it.configuration).withRequestRetryBackoffLimit(0).build() }
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(KubernetesClientBuilder().withConfig(config).build(), "example-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager, probeIntervalMs = 3_600_000, metricsPollIntervalMs = 3_600_000, usageSampleIntervalMs = 0)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientQuotaUsageTest", manager = manager, servers = listOf(server))
    }

    /**
     * A quota in [namespace]; [hard] is the spec. [statusHard] and [statusUsed] make a status: with only
     * [statusUsed] the status has no `hard`, as a quota whose controller has counted usage but not yet
     * mirrored the limits.
     */
    private fun seedQuota(
        namespace: String,
        name: String,
        hard: Map<String, String>,
        statusHard: Map<String, String>? = null,
        statusUsed: Map<String, String>? = null,
    ) {
        val quota = ResourceQuotaBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
            .withNewSpec()
            .also { spec -> hard.forEach { (key, value) -> spec.addToHard(key, Quantity(value)) } }
            .endSpec()
            .also { builder ->
                if (statusHard != null || statusUsed != null) {
                    val status = builder.withNewStatus()
                    statusHard?.forEach { (key, value) -> status.addToHard(key, Quantity(value)) }
                    statusUsed?.forEach { (key, value) -> status.addToUsed(key, Quantity(value)) }
                    status.endStatus()
                }
            }
            .build()
        server.createClient().use { it.resourceQuotas().inNamespace(namespace).resource(quota).create() }
    }

    @Test
    fun `a quota with no status hard shows the spec hard values, sorted, with the used it has`() {
        seedQuota(
            "team-a",
            "compute-quota",
            hard = mapOf("requests.cpu" to "2", "requests.memory" to "4Gi", "limits.cpu" to "4"),
            statusUsed = mapOf("requests.cpu" to "1"),
        )

        assertEquals(
            listOf(
                QuotaUsageRow(resource = "limits.cpu", used = "0", hard = "4", fraction = 0f),
                QuotaUsageRow(resource = "requests.cpu", used = "1", hard = "2", fraction = 0.5f),
                QuotaUsageRow(resource = "requests.memory", used = "0", hard = "4Gi", fraction = 0f),
            ),
            client.getResourceQuotaUsage("compute-quota", "team-a"),
        )
    }

    @Test
    fun `a quota with a status hard shows the status rows and fractions, not the spec`() {
        seedQuota(
            "team-b",
            "compute-quota",
            hard = mapOf("requests.cpu" to "8", "limits.cpu" to "16"),
            statusHard = mapOf("requests.cpu" to "4", "pods" to "10"),
            statusUsed = mapOf("requests.cpu" to "1", "pods" to "10"),
        )

        assertEquals(
            listOf(
                QuotaUsageRow(resource = "pods", used = "10", hard = "10", fraction = 1f),
                QuotaUsageRow(resource = "requests.cpu", used = "1", hard = "4", fraction = 0.25f),
            ),
            client.getResourceQuotaUsage("compute-quota", "team-b"),
        )
    }
}
