package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.PodInfo
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
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
 * Tests for [ReactiveKubeClient.listJobPods] and [ReactiveKubeClient.sortPodsNewestFirst].
 *
 * CAUTION: the fabric8 CRUD mock overwrites `metadata.creationTimestamp` with
 * now() (second-truncated) and `metadata.uid` with a fresh UUID on every POST,
 * so neither can be seeded through the mock server. The mock-server tests below
 * therefore assert membership/filtering only (never order), and owner-ref uids
 * are always read back from the created objects rather than hardcoded. Ordering
 * (including stability and empty-timestamp-last) is covered separately by a
 * pure test against hand-built [PodInfo] fixtures.
 */
class ReactiveKubeClientListJobPodsTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var jobUid: String

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
            val createdJob = seed.batch().v1().jobs().inNamespace("default").resource(
                JobBuilder()
                    .withNewMetadata().withName("example-job").withNamespace("default").endMetadata()
                    .build(),
            ).create()
            jobUid = createdJob.metadata.uid

            val createdOtherJob = seed.batch().v1().jobs().inNamespace("default").resource(
                JobBuilder()
                    .withNewMetadata().withName("other-job").withNamespace("default").endMetadata()
                    .build(),
            ).create()
            val otherJobUid = createdOtherJob.metadata.uid

            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("example-job-aaaaa")
                    .withNamespace("default")
                    .withOwnerReferences(OwnerReferenceBuilder().withUid(jobUid).build())
                    .endMetadata()
                    .build(),
            ).create()

            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("example-job-bbbbb")
                    .withNamespace("default")
                    .withOwnerReferences(OwnerReferenceBuilder().withUid(jobUid).build())
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
                    .withName("other-owner")
                    .withNamespace("default")
                    .withOwnerReferences(OwnerReferenceBuilder().withUid(otherJobUid).build())
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
        shutdownCleanly(scope, label = "ReactiveKubeClientListJobPodsTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `listJobPods returns only pods owned by the given job uid`() = runBlocking {
        val result = client.listJobPods("default", jobUid)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val names = result.getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("example-job-aaaaa", "example-job-bbbbb"), names)
    }

    @Test
    fun `listJobPods returns an empty list for a uid that owns nothing`() = runBlocking {
        val result = client.listJobPods("default", "job-uid-404")
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `listJobPods returns an empty list for a namespace with no pods`() = runBlocking {
        val result = client.listJobPods("empty-ns", jobUid)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `sortPodsNewestFirst orders newest to oldest with empty timestamps last`() {
        val newest = fakePod(name = "newest", creationTimestamp = "2026-01-02T00:00:00Z")
        val oldest = fakePod(name = "oldest", creationTimestamp = "2026-01-01T00:00:00Z")
        val unknown = fakePod(name = "unknown", creationTimestamp = "")

        val sorted = client.sortPodsNewestFirst(listOf(oldest, unknown, newest))

        assertEquals(listOf("newest", "oldest", "unknown"), sorted.map { it.name })
    }

    @Test
    fun `sortPodsNewestFirst is stable for pods with equal timestamps`() {
        val first = fakePod(name = "first", creationTimestamp = "2026-01-01T00:00:00Z")
        val second = fakePod(name = "second", creationTimestamp = "2026-01-01T00:00:00Z")

        val sorted = client.sortPodsNewestFirst(listOf(first, second))

        assertEquals(listOf("first", "second"), sorted.map { it.name })
    }

    private fun fakePod(name: String, creationTimestamp: String) = PodInfo(
        uid = "uid-$name",
        name = name,
        namespace = "example-ns",
        status = "Running",
        ready = "1/1",
        restarts = 0,
        age = "1d",
        creationTimestamp = creationTimestamp,
        node = "node-1",
        ip = "10.0.0.1",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = emptyList<ContainerInfo>(),
        phase = "Running",
    )
}
