package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.batch.v1.CronJob
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.VersionInfo
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import java.time.Duration
import java.time.Instant
import java.util.Queue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The demo cluster's CronJobs as the seed writes them: their age, and the history of log-rotation's runs. */
class MockClusterSeedCronJobsTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient
    private var seedStartMillis = 0L
    private var seedEndMillis = 0L

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
        val store = DemoClusterStore(responses)
        server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            responses,
            store,
            false,
            VersionInfo.Builder().withMajor("1").withMinor("30").withGitVersion("v1.30.2").build(),
        )
        server.init()
        client = server.createClient()
        seedStartMillis = System.currentTimeMillis()
        seedResources(client, store::backdate)
        seedEndMillis = System.currentTimeMillis()
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(
            label = "MockClusterSeedCronJobsTest",
            client = if (::client.isInitialized) client else null,
            servers = if (::server.isInitialized) listOf(server) else emptyList(),
        )
    }

    private fun cronJobs(): List<CronJob> = client.batch().v1().cronjobs().inAnyNamespace().list().items

    private fun slotOf(millis: Long): Long = millis / 60_000 / 15 * 15

    private fun runsOf(cronJob: CronJob): List<Job> = client.batch().v1().jobs().inNamespace(cronJob.metadata.namespace).list().items
        .filter { job -> job.metadata.ownerReferences.any { it.uid == cronJob.metadata.uid } }
        .sortedBy { it.metadata.name }

    @Test
    fun `every seeded CronJob is about a day old`() {
        val all = cronJobs()
        assertEquals(setOf("nightly-backup", "db-vacuum", "log-rotation", "cert-renewal"), all.map { it.metadata.name }.toSet())

        for (cronJob in all) {
            val age = Duration.between(Instant.parse(cronJob.metadata.creationTimestamp), Instant.now())
            assertTrue(age.toMinutes() in 1438L..1442L, "${cronJob.metadata.name} is ${age.toMinutes()} minutes old")
        }
    }

    @Test
    fun `log-rotation has three finished runs on its 15-minute slots, each with a pod that ended 4 minutes after the start`() {
        val cronJob = assertNotNull(client.batch().v1().cronjobs().inNamespace("monitoring").withName("log-rotation").get())
        assertEquals("*/15 * * * *", cronJob.spec.schedule)

        val runs = runsOf(cronJob)

        assertEquals(3, runs.size, runs.map { it.metadata.name }.toString())
        val minutes = runs.map { it.metadata.name.removePrefix("log-rotation-").toLong() }
        assertEquals(listOf(minutes[0], minutes[0] + 15, minutes[0] + 30), minutes, "the runs are 15 minutes apart")
        assertEquals(0L, minutes[0] % 15, "the runs are on the schedule's slots")
        assertTrue(minutes.last() in slotOf(seedStartMillis) - 15..slotOf(seedEndMillis) - 15, "the newest run is the slot before the current one: $minutes")
        for ((run, suffix) in runs.zip(listOf("k2m4p", "q7r9t", "b5x8z"))) {
            val start = Instant.ofEpochSecond(run.metadata.name.removePrefix("log-rotation-").toLong() * 60)
            val finish = start.plusSeconds(240)
            assertEquals(start.toString(), run.metadata.creationTimestamp)
            assertEquals(start.toString(), run.status.startTime)
            assertEquals(finish.toString(), run.status.completionTime)
            assertNull(run.status.active)
            assertEquals(1, run.status.succeeded)

            val pod = assertNotNull(client.pods().inNamespace("monitoring").withName("${run.metadata.name}-$suffix").get(), "the pod of ${run.metadata.name}")
            assertEquals(listOf(run.metadata.uid), pod.metadata.ownerReferences.map { it.uid })
            assertEquals("Succeeded", pod.status.phase)
            assertEquals(start.toString(), pod.metadata.creationTimestamp)
            val terminated = assertNotNull(pod.status.containerStatuses.single().state.terminated)
            assertEquals(finish.toString(), terminated.finishedAt)
            assertEquals(0, terminated.exitCode)
        }
        assertEquals(runs.size, client.pods().inNamespace("monitoring").list().items.count { pod -> pod.metadata.name.startsWith("log-rotation-") })

        val newestStart = Instant.ofEpochSecond(minutes.last() * 60)
        assertEquals(newestStart.toString(), cronJob.status.lastScheduleTime)
        assertEquals(newestStart.plusSeconds(240).toString(), cronJob.status.lastSuccessfulTime)
    }
}
