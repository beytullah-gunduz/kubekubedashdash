package com.kubekubedashdash.util

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.ui.screens.generic.kindOverviewSections
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ReactiveKubeClient.listCronJobPods] (CronJob → owned Jobs →
 * their pods) and the `"Job"` / `"CronJob"` branches of `kindExtraTabs`.
 *
 * CAUTION: the fabric8 CRUD mock regenerates `metadata.uid` on every POST, so
 * every owner-ref uid is read back from the created object, never hardcoded —
 * a ConfigMap stands in for the CronJob since the method only uses its uid.
 */
class ReactiveKubeClientCronJobPodsTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var cronJobUid: String

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
            val cron = seed.configMaps().inNamespace("default").resource(
                ConfigMapBuilder()
                    .withNewMetadata().withName("nightly-cron").withNamespace("default").endMetadata()
                    .build(),
            ).create()
            cronJobUid = cron.metadata.uid

            val otherCron = seed.configMaps().inNamespace("default").resource(
                ConfigMapBuilder()
                    .withNewMetadata().withName("other-cron").withNamespace("default").endMetadata()
                    .build(),
            ).create()
            val otherCronUid = otherCron.metadata.uid

            fun createJob(name: String, ownerUid: String?): String {
                val builder = JobBuilder().withNewMetadata().withName(name).withNamespace("default")
                if (ownerUid != null) {
                    builder.withOwnerReferences(OwnerReferenceBuilder().withUid(ownerUid).build())
                }
                return seed.batch().v1().jobs().inNamespace("default")
                    .resource(builder.endMetadata().build())
                    .create().metadata.uid
            }

            fun createPod(name: String, ownerUid: String?) {
                val builder = PodBuilder().withNewMetadata().withName(name).withNamespace("default")
                if (ownerUid != null) {
                    builder.withOwnerReferences(OwnerReferenceBuilder().withUid(ownerUid).build())
                }
                seed.pods().inNamespace("default").resource(builder.endMetadata().build()).create()
            }

            val run1Uid = createJob("nightly-run-1", cronJobUid)
            val run2Uid = createJob("nightly-run-2", cronJobUid)
            val otherJobUid = createJob("other-cron-run", otherCronUid)
            val standaloneJobUid = createJob("standalone-job", null)

            createPod("nightly-run-1-aaaaa", run1Uid)
            createPod("nightly-run-2-bbbbb", run2Uid)
            createPod("nightly-run-2-ccccc", run2Uid)
            createPod("other-cron-run-ddddd", otherJobUid)
            createPod("standalone-job-eeeee", standaloneJobUid)
            createPod("ownerless", null)
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
        scope.cancel()
        manager.close()
        server.destroy()
    }

    @Test
    fun `listCronJobPods returns only pods of jobs owned by the cronjob`() = runBlocking {
        val result = client.listCronJobPods("default", cronJobUid)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val names = result.getOrThrow().map { it.name }.toSet()
        assertEquals(
            setOf("nightly-run-1-aaaaa", "nightly-run-2-bbbbb", "nightly-run-2-ccccc"),
            names,
        )
    }

    @Test
    fun `listCronJobPods returns an empty list for a uid that owns no jobs`() = runBlocking {
        val result = client.listCronJobPods("default", "cron-uid-404")
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `listCronJobPods returns an empty list for a namespace with no jobs or pods`() = runBlocking {
        val result = client.listCronJobPods("empty-ns", cronJobUid)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `kindOverviewSections wires a pods section for namespaced Jobs and CronJobs`() {
        assertEquals(listOf("pods"), kindOverviewSections("Job", genericRes(namespace = "default"), client).map { it.key })
        assertEquals(listOf("pods"), kindOverviewSections("CronJob", genericRes(namespace = "default"), client).map { it.key })
    }

    @Test
    fun `kindOverviewSections returns no pods section for cluster-scoped Jobs and CronJobs`() {
        assertTrue(kindOverviewSections("Job", genericRes(namespace = null), client).isEmpty())
        assertTrue(kindOverviewSections("CronJob", genericRes(namespace = null), client).isEmpty())
    }

    private fun genericRes(namespace: String?) = GenericResourceInfo(
        uid = cronJobUid,
        name = "nightly-cron",
        namespace = namespace,
        status = null,
        age = "1d",
        labels = emptyMap(),
        annotations = emptyMap(),
    )
}
