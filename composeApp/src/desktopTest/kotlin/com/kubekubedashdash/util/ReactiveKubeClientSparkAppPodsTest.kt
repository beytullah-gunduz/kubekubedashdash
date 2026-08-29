package com.kubekubedashdash.util

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.ui.screens.generic.kindOverviewSections
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ReactiveKubeClient.listSparkApplicationPods] and the
 * `"SparkApplication"` branch of `kindExtraTabs`.
 *
 * CAUTION: the fabric8 CRUD mock overwrites `metadata.creationTimestamp` with
 * now() (second-truncated) and `metadata.uid` with a fresh UUID on every POST,
 * so neither can be seeded through the mock server. The mock-server tests
 * below therefore assert membership/dedupe/grouping only (never timestamp
 * order), and owner-ref uids are always read back from the created objects
 * rather than hardcoded — the method never fetches the CR itself, so a
 * ConfigMap stands in for the SparkApplication and only its uid matters.
 */
class ReactiveKubeClientSparkAppPodsTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var appUid: String
    private val appName = "spark-pi"

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            HashMap(),
            KubernetesCrudDispatcher(),
            false,
        )
        server.init()

        val seed = server.createClient()
        try {
            val createdApp = seed.configMaps().inNamespace("default").resource(
                ConfigMapBuilder()
                    .withNewMetadata().withName("spark-pi").withNamespace("default").endMetadata()
                    .build(),
            ).create()
            appUid = createdApp.metadata.uid

            val createdOtherApp = seed.configMaps().inNamespace("default").resource(
                ConfigMapBuilder()
                    .withNewMetadata().withName("other-app").withNamespace("default").endMetadata()
                    .build(),
            ).create()
            val otherAppUid = createdOtherApp.metadata.uid

            val createdDriver = seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("driver")
                    .withNamespace("default")
                    .withOwnerReferences(OwnerReferenceBuilder().withUid(appUid).build())
                    .addToLabels("spark-role", "driver")
                    .endMetadata()
                    .build(),
            ).create()
            val driverUid = createdDriver.metadata.uid

            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("exec-1")
                    .withNamespace("default")
                    .withOwnerReferences(OwnerReferenceBuilder().withUid(driverUid).build())
                    .endMetadata()
                    .build(),
            ).create()

            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("exec-2")
                    .withNamespace("default")
                    .withOwnerReferences(OwnerReferenceBuilder().withUid(driverUid).build())
                    .endMetadata()
                    .build(),
            ).create()

            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("labeled-only")
                    .withNamespace("default")
                    .addToLabels("sparkoperator.k8s.io/app-name", appName)
                    .endMetadata()
                    .build(),
            ).create()

            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("labeled-and-owned")
                    .withNamespace("default")
                    .withOwnerReferences(OwnerReferenceBuilder().withUid(appUid).build())
                    .addToLabels("sparkoperator.k8s.io/app-name", appName)
                    .endMetadata()
                    .build(),
            ).create()

            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("unrelated")
                    .withNamespace("default")
                    .endMetadata()
                    .build(),
            ).create()

            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("other-app")
                    .withNamespace("default")
                    .withOwnerReferences(OwnerReferenceBuilder().withUid(otherAppUid).build())
                    .endMetadata()
                    .build(),
            ).create()
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientSparkAppPodsTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `listSparkApplicationPods returns the driver, executors and labeled pods`() = runBlocking {
        val result = client.listSparkApplicationPods("default", appUid, appName)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val names = result.getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("driver", "exec-1", "exec-2", "labeled-only", "labeled-and-owned"), names)
    }

    @Test
    fun `listSparkApplicationPods dedupes a pod matched by both owner ref and label`() = runBlocking {
        val result = client.listSparkApplicationPods("default", appUid, appName)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        assertEquals(5, result.getOrThrow().size)
    }

    @Test
    fun `listSparkApplicationPods places the driver group before the rest`() = runBlocking {
        val result = client.listSparkApplicationPods("default", appUid, appName)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val names = result.getOrThrow().map { it.name }
        val driverGroupPositions = listOf(names.indexOf("driver"), names.indexOf("labeled-and-owned"))
        val restPositions = listOf(names.indexOf("exec-1"), names.indexOf("exec-2"), names.indexOf("labeled-only"))

        assertTrue(
            driverGroupPositions.max() < restPositions.min(),
            "expected driver group before the rest, got order: $names",
        )
    }

    @Test
    fun `listSparkApplicationPods returns an empty list for a uid and name that match nothing`() = runBlocking {
        val result = client.listSparkApplicationPods("default", "app-uid-404", "no-such-app")
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `listSparkApplicationPods returns an empty list for a namespace with no pods`() = runBlocking {
        val result = client.listSparkApplicationPods("empty-ns", appUid, appName)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `selectSparkApplicationPods keeps the driver group first even when the rest is newer`() {
        // Hand-built pods with explicit uids/timestamps (the CRUD mock can't
        // seed either): the driver is the OLDEST pod, so plain newest-first
        // ordering without the driver-group partition would sink it to last.
        val driver = fixedPod(
            name = "driver",
            uid = "uid-driver",
            createdAt = "2026-01-01T00:00:00Z",
            ownerUid = "uid-app",
        )
        val exec1 = fixedPod(
            name = "exec-1",
            uid = "uid-exec-1",
            createdAt = "2026-01-02T00:00:00Z",
            ownerUid = "uid-driver",
        )
        val labeledOnly = fixedPod(
            name = "labeled-only",
            uid = "uid-labeled",
            createdAt = "2026-01-03T00:00:00Z",
            labels = mapOf("sparkoperator.k8s.io/app-name" to appName),
        )

        val result = client.selectSparkApplicationPods(listOf(labeledOnly, exec1, driver), "uid-app", appName)

        assertEquals(listOf("driver", "labeled-only", "exec-1"), result.map { it.name })
    }

    @Test
    fun `kindOverviewSections wires a pods section for a namespaced SparkApplication`() {
        val sections = kindOverviewSections("SparkApplication", genericRes(namespace = "default"), client)
        assertEquals(listOf("pods"), sections.map { it.key })
    }

    @Test
    fun `kindOverviewSections returns no sections for a cluster-scoped SparkApplication`() {
        val sections = kindOverviewSections("SparkApplication", genericRes(namespace = null), client)
        assertTrue(sections.isEmpty())
    }

    private fun fixedPod(
        name: String,
        uid: String,
        createdAt: String,
        ownerUid: String? = null,
        labels: Map<String, String> = emptyMap(),
    ): Pod {
        val builder = PodBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace("default")
            .withUid(uid)
            .withCreationTimestamp(createdAt)
            .withLabels<String, String>(labels)
        if (ownerUid != null) {
            builder.withOwnerReferences(OwnerReferenceBuilder().withUid(ownerUid).build())
        }
        return builder.endMetadata().build()
    }

    private fun genericRes(namespace: String?) = GenericResourceInfo(
        uid = appUid,
        name = appName,
        namespace = namespace,
        status = null,
        age = "1d",
        labels = emptyMap(),
        annotations = emptyMap(),
    )
}
