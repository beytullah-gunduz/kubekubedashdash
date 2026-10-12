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
 * The Jobs the demo simulator creates, as the usage history charts meet them: CronJob Jobs named after the
 * minute they were scheduled for, pods named like generated ones, and a pod that ends with its Job.
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

    @Test
    fun `a CronJob's Jobs are named after the minute they were scheduled for, never one minute twice`() {
        val firstMinute = System.currentTimeMillis() / 60_000

        spawnJobs(150)

        val owned = jobs().filter { job -> job.metadata.ownerReferences.any { it.kind == "CronJob" } }
        assertTrue(owned.size >= 40, "only ${owned.size} of the Jobs belong to a CronJob")
        val minutesByCronJob = owned.groupBy({ job -> job.metadata.ownerReferences.single { it.kind == "CronJob" }.name }) { job ->
            val owner = job.metadata.ownerReferences.single { it.kind == "CronJob" }.name
            assertTrue(Regex("${Regex.escape(owner)}-[0-9]{8}").matches(job.metadata.name), job.metadata.name)
            job.metadata.name.substringAfterLast('-').toLong()
        }
        assertEquals(CRON_JOBS.map { it.first }.toSet(), minutesByCronJob.keys)
        for ((cronJob, minutes) in minutesByCronJob) {
            assertEquals(minutes.size, minutes.toSet().size, "$cronJob scheduled a minute twice: $minutes")
            assertTrue(minutes.min() >= firstMinute, "$cronJob has a Job from before the test began")
            assertEquals(minutes.size - 1L, minutes.max() - minutes.min(), "$cronJob skipped a minute: ${minutes.sorted()}")
        }
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
