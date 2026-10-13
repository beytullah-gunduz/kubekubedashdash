package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder
import io.fabric8.kubernetes.api.model.StatusBuilder
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
 * The quota lines of the namespace panel's Total charts: the tightest ResourceQuota hard values of a
 * namespace, from the quotas that cap the whole namespace. Loopback mock only: the CRUD store serves the
 * quotas, canned answers stand in for the failures.
 */
class ReactiveKubeClientQuotaCeilingsTest {

    private companion object {
        const val GIB = 1L shl 30
    }

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
        shutdownCleanly(scope, label = "ReactiveKubeClientQuotaCeilingsTest", manager = manager, servers = listOf(server))
    }

    /** A quota in [namespace]; [hard] is the spec, [statusHard] the status when the quota has been reconciled. */
    private fun seedQuota(
        namespace: String,
        name: String,
        hard: Map<String, String>,
        statusHard: Map<String, String>? = null,
        scopes: List<String> = emptyList(),
        scopeSelectorOnPriorityClass: Boolean = false,
    ) {
        val quota = ResourceQuotaBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
            .withNewSpec()
            .withScopes(scopes)
            .also { spec ->
                hard.forEach { (key, value) -> spec.addToHard(key, Quantity(value)) }
                if (scopeSelectorOnPriorityClass) {
                    spec.withNewScopeSelector().addNewMatchExpression().withScopeName("PriorityClass").withOperator("In").withValues("high").endMatchExpression().endScopeSelector()
                }
            }
            .endSpec()
            .also { builder ->
                if (statusHard != null) {
                    val status = builder.withNewStatus()
                    statusHard.forEach { (key, value) -> status.addToHard(key, Quantity(value)) }
                    status.endStatus()
                }
            }
            .build()
        server.createClient().use { it.resourceQuotas().inNamespace(namespace).resource(quota).create() }
    }

    private val compute = mapOf("requests.cpu" to "4", "limits.cpu" to "8", "requests.memory" to "8Gi", "limits.memory" to "16Gi")

    @Test
    fun `a namespace without a quota has no ceilings, whatever another namespace holds`() {
        seedQuota("production", "compute-quota", compute)

        assertEquals(QuotaCeilings(), client.getNamespaceQuotaCeilings("team-a"))
    }

    @Test
    fun `one quota gives its four hard values in cores and bytes`() {
        seedQuota("production", "compute-quota", compute)

        assertEquals(
            QuotaCeilings(requestsCpu = 4.0, limitsCpu = 8.0, requestsMemory = 8.0 * GIB, limitsMemory = 16.0 * GIB),
            client.getNamespaceQuotaCeilings("production"),
        )
        assertEquals(8_589_934_592.0, client.getNamespaceQuotaCeilings("production").requestsMemory)
        assertEquals(17_179_869_184.0, client.getNamespaceQuotaCeilings("production").limitsMemory)
    }

    @Test
    fun `a quota that sets only some keys leaves the others null, and milli-cores are cores`() {
        seedQuota("production", "compute-quota", mapOf("requests.cpu" to "500m", "limits.memory" to "512Mi", "pods" to "20"))

        assertEquals(
            QuotaCeilings(requestsCpu = 0.5, limitsMemory = 512.0 * (1L shl 20)),
            client.getNamespaceQuotaCeilings("production"),
        )
    }

    @Test
    fun `the tightest of several unscoped quotas wins per key`() {
        seedQuota("monitoring", "compute-quota", compute)
        seedQuota("monitoring", "compute-quota-2", mapOf("requests.cpu" to "2", "limits.memory" to "32Gi"))

        assertEquals(
            QuotaCeilings(requestsCpu = 2.0, limitsCpu = 8.0, requestsMemory = 8.0 * GIB, limitsMemory = 16.0 * GIB),
            client.getNamespaceQuotaCeilings("monitoring"),
        )
    }

    @Test
    fun `a quota with scopes or a scope selector caps a subset of the pods and is ignored`() {
        seedQuota("default", "compute-quota", mapOf("requests.cpu" to "1"), scopes = listOf("BestEffort"))
        seedQuota("production", "compute-quota", mapOf("requests.cpu" to "1"), scopeSelectorOnPriorityClass = true)
        seedQuota("production", "compute-quota-2", mapOf("requests.cpu" to "4"))

        assertEquals(QuotaCeilings(), client.getNamespaceQuotaCeilings("default"))
        assertEquals(QuotaCeilings(requestsCpu = 4.0), client.getNamespaceQuotaCeilings("production"))
    }

    @Test
    fun `cpu and memory are the aliases of the requests keys`() {
        seedQuota("team-a", "compute-quota", mapOf("cpu" to "3", "memory" to "2Gi"))
        seedQuota("team-b", "compute-quota", mapOf("cpu" to "3", "requests.cpu" to "2"))

        assertEquals(QuotaCeilings(requestsCpu = 3.0, requestsMemory = 2.0 * GIB), client.getNamespaceQuotaCeilings("team-a"))
        assertEquals(QuotaCeilings(requestsCpu = 2.0), client.getNamespaceQuotaCeilings("team-b"))
    }

    @Test
    fun `the status hard values win over the spec's`() {
        seedQuota("production", "compute-quota", mapOf("requests.cpu" to "4"), statusHard = mapOf("requests.cpu" to "3"))
        seedQuota("monitoring", "compute-quota", mapOf("requests.cpu" to "4"), statusHard = emptyMap())

        assertEquals(QuotaCeilings(requestsCpu = 3.0), client.getNamespaceQuotaCeilings("production"))
        assertEquals(QuotaCeilings(requestsCpu = 4.0), client.getNamespaceQuotaCeilings("monitoring"))
    }

    @Test
    fun `a server error reads as no ceilings`() {
        server.expect().get().withPath("/api/v1/namespaces/team-b/resourcequotas")
            .andReturn(500, StatusBuilder().withCode(500).withReason("InternalError").build())
            .once()

        assertEquals(QuotaCeilings(), client.getNamespaceQuotaCeilings("team-b"))
    }

    @Test
    fun `a refused list reads as no ceilings`() {
        server.expect().get().withPath("/api/v1/namespaces/production/resourcequotas")
            .andReturn(403, StatusBuilder().withCode(403).withReason("Forbidden").build())
            .once()

        assertEquals(QuotaCeilings(), client.getNamespaceQuotaCeilings("production"))
    }
}
