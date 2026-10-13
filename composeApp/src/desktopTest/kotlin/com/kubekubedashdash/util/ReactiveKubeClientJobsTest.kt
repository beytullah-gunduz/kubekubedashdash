package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.WorkloadRun
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Job rows carry the creation time and run data the usage history charts mark runs with. */
class ReactiveKubeClientJobsTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var storedTimestamp: String

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        val seed = server.createClient()
        try {
            val created = seed.batch().v1().jobs().inNamespace("default").resource(
                JobBuilder()
                    .withNewMetadata()
                    .withName("nightly-backup-29123456")
                    .withNamespace("default")
                    .withOwnerReferences(
                        OwnerReferenceBuilder().withApiVersion("batch/v1").withKind("CronJob").withName("nightly-backup").withUid("uid-cron-1").withController(true).build(),
                    )
                    .endMetadata()
                    .withNewSpec().withCompletionMode("Indexed").withCompletions(2).endSpec()
                    .withNewStatus()
                    .withStartTime("2026-10-12T08:00:00Z")
                    .withCompletionTime("2026-10-12T08:05:30Z")
                    .withSucceeded(2)
                    .endStatus()
                    .build(),
            ).create()
            // The CRUD mock may stamp its own time: the row must carry whatever the server stored.
            storedTimestamp = created.metadata.creationTimestamp.orEmpty()
        } finally {
            seed.close()
        }

        val config = server.createClient().use { ConfigBuilder(it.configuration).withRequestRetryBackoffLimit(0).build() }
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(KubernetesClientBuilder().withConfig(config).build(), "example-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientJobsTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `a Job row carries the creation time the server stored and its run, beside its owners`() = runBlocking<Unit> {
        val state = withTimeout(15_000) { client.jobs.first { it is ResourceState.Success && it.data.isNotEmpty() } }

        val row = (state as ResourceState.Success).data.single()
        assertTrue(storedTimestamp.isNotBlank(), "the mock stored no creation time")
        assertEquals(storedTimestamp, row.creationTimestamp)
        assertEquals(WorkloadRun(startTime = "2026-10-12T08:00:00Z", endTime = "2026-10-12T08:05:30Z", indexed = true), row.run)
        assertEquals(listOf("uid-cron-1"), row.owners.map { it.uid })
        assertEquals("Complete", row.status)
    }
}
