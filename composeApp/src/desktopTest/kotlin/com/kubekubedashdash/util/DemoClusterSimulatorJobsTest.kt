package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Jobs the demo simulator creates, as the usage history charts meet them: CronJob Jobs run on their
 * schedule's slots and named after the slot's minute, pods named like generated ones, and a pod that ends with its Job.
 */
class DemoClusterSimulatorJobsTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient
    private lateinit var simulator: DemoClusterSimulator

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        client = server.createClient()
        simulator = DemoClusterSimulator(client)
        client.nodes().resource(NodeBuilder().withNewMetadata().withName("mock-node-1").endMetadata().build()).create()
        for ((name, ns) in CRON_JOBS) {
            client.batch().v1().cronjobs().inNamespace(ns).resource(CronJobBuilder().withNewMetadata().withName(name).withNamespace(ns).endMetadata().build()).create()
        }
    }

    @AfterTest
    fun tearDown() {
        if (::simulator.isInitialized) simulator.stop()
        shutdownCleanly(
            label = "DemoClusterSimulatorJobsTest",
            client = if (::client.isInitialized) client else null,
            servers = if (::server.isInitialized) listOf(server) else emptyList(),
        )
    }

    private fun jobs(): List<Job> = client.batch().v1().jobs().inAnyNamespace().list().items

    private fun pods(): List<Pod> = client.pods().inAnyNamespace().list().items

    private fun podOf(job: Job): Pod = assertNotNull(pods().singleOrNull { pod -> pod.metadata.ownerReferences.any { it.uid == job.metadata.uid } }, "the pod of ${job.metadata.name}")

    // Each Job gets a CronJob owner or none, picked at random, so a test spawns enough of them to see every owner.
    private fun spawnJobs(count: Int) {
        repeat(count) { simulator.spawnJob() }
    }

    // The names of the Jobs a CronJob owns, oldest slot first.
    private fun ownedBy(cronJob: String): List<String> = jobs()
        .filter { job -> job.metadata.ownerReferences.any { it.kind == "CronJob" && it.name == cronJob } }
        .map { it.metadata.name }
        .sorted()

    @Test
    fun `log-rotation runs once per 15-minute slot, named after the slot's minute, and the daily and weekly CronJobs never run`() {
        var now = Instant.parse("2026-10-13T08:37:20Z").toEpochMilli()
        simulator.nowMillis = { now }
        val slot = now / 60_000 / 15 * 15

        spawnJobs(100)

        assertEquals(listOf("log-rotation-$slot"), ownedBy("log-rotation"))
        assertEquals(0, slot % 15, "the slot is a minute the schedule fires on")

        now += 5 * 60_000
        spawnJobs(100)
        assertEquals(listOf("log-rotation-$slot"), ownedBy("log-rotation"), "the slot has not changed")

        now += 10 * 60_000
        spawnJobs(100)
        assertEquals(listOf("log-rotation-$slot", "log-rotation-${slot + 15}"), ownedBy("log-rotation"))

        assertEquals(emptyList(), ownedBy("nightly-backup"))
        assertEquals(emptyList(), ownedBy("db-vacuum"))
        val free = jobs().filter { job -> job.metadata.ownerReferences.isEmpty() }
        assertTrue(free.size >= 250, "only ${free.size} of the Jobs are free-standing")
    }

    // A Job of log-rotation that the cluster already holds, named after the minute it was scheduled for.
    private fun existingRun(minute: Long) {
        val cronJob = assertNotNull(client.batch().v1().cronjobs().inNamespace("monitoring").withName("log-rotation").get())
        client.batch().v1().jobs().inNamespace("monitoring")
            .resource(MockClusterProvider.buildJob("log-rotation-$minute", "monitoring", "log-rotator", "busybox:1.36", cronJob = cronJob))
            .create()
    }

    @Test
    fun `log-rotation does not run a slot again that its newest Job in the cluster was scheduled for`() {
        val now = Instant.parse("2026-10-13T08:37:20Z").toEpochMilli()
        simulator.nowMillis = { now }
        val slot = now / 60_000 / 15 * 15
        existingRun(slot)

        spawnJobs(100)

        assertEquals(listOf("log-rotation-$slot"), ownedBy("log-rotation"))
    }

    @Test
    fun `log-rotation runs the current slot when its newest Job in the cluster is an earlier one`() {
        val now = Instant.parse("2026-10-13T08:37:20Z").toEpochMilli()
        simulator.nowMillis = { now }
        val slot = now / 60_000 / 15 * 15
        existingRun(slot - 15)

        spawnJobs(100)

        assertEquals(listOf("log-rotation-${slot - 15}", "log-rotation-$slot"), ownedBy("log-rotation"))
    }

    @Test
    fun `every Job has one pod named like a generated one`() {
        spawnJobs(40)

        val all = jobs()
        assertTrue(all.isNotEmpty())
        for (job in all) {
            val pod = podOf(job)
            assertTrue(Regex("${Regex.escape(job.metadata.name)}-[bcdfghjklmnpqrstvwxz2456789]{5}").matches(pod.metadata.name), pod.metadata.name)
            assertEquals("Running", pod.status.phase)
        }
    }

    @Test
    fun `a Job that succeeds ends its pod with exit code 0, one that fails with exit code 1`() {
        spawnJobs(10)
        val (succeeding, failing) = jobs().take(2)
        val succeedingPod = podOf(succeeding)
        val failingPod = podOf(failing)

        simulator.finishJob(succeeding.metadata.namespace, succeeding.metadata.name, succeeded = true, podName = succeedingPod.metadata.name)
        simulator.finishJob(failing.metadata.namespace, failing.metadata.name, succeeded = false, podName = failingPod.metadata.name)

        for ((job, exitCode, phase) in listOf(Triple(succeeding, 0, "Succeeded"), Triple(failing, 1, "Failed"))) {
            val pod = podOf(job)
            assertEquals(phase, pod.status.phase)
            val terminated = assertNotNull(pod.status.containerStatuses.first().state.terminated)
            assertEquals(exitCode, terminated.exitCode)
            val finishedAt = Instant.parse(assertNotNull(terminated.finishedAt))
            assertTrue(finishedAt >= Instant.parse(pod.metadata.creationTimestamp), "the pod ended before it was created")
            assertEquals(pod.spec.containers.first().name, pod.status.containerStatuses.first().name)
            assertNotNull(client.batch().v1().jobs().inNamespace(job.metadata.namespace).withName(job.metadata.name).get().status.completionTime)
        }
    }

    @Test
    fun `finishing a Job whose pod is gone, or that never had one, is harmless`() {
        spawnJobs(5)
        val job = jobs().first()
        val pod = podOf(job)
        client.pods().inNamespace(pod.metadata.namespace).withName(pod.metadata.name).delete()

        simulator.finishJob(job.metadata.namespace, job.metadata.name, succeeded = true, podName = pod.metadata.name)
        simulator.finishJob(job.metadata.namespace, job.metadata.name, succeeded = true, podName = null)

        assertNotNull(client.batch().v1().jobs().inNamespace(job.metadata.namespace).withName(job.metadata.name).get().status.completionTime)
    }

    @Test
    fun `deleting a Job deletes its pod with it`() {
        spawnJobs(5)
        val job = jobs().first()
        val pod = podOf(job)

        simulator.deleteJob(job.metadata.namespace, job.metadata.name, pod.metadata.name)

        assertNull(client.batch().v1().jobs().inNamespace(job.metadata.namespace).withName(job.metadata.name).get())
        assertNull(client.pods().inNamespace(pod.metadata.namespace).withName(pod.metadata.name).get())
    }

    private companion object {
        val CRON_JOBS = listOf("nightly-backup" to "production", "log-rotation" to "monitoring", "db-vacuum" to "production")
    }
}
