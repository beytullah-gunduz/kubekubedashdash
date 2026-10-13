package com.kubekubedashdash.util

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.InClusterMetricsSource
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.services.metricshistory.NoSourceReason
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PodSetMode
import com.kubekubedashdash.services.metricshistory.PrometheusDetector
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.ReleaseCheckSupport
import com.kubekubedashdash.services.metricshistory.ScopeKind
import com.kubekubedashdash.services.metricshistory.ScopeRef
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.services.metricshistory.cronJobUsage
import com.kubekubedashdash.services.metricshistory.deploymentPodSet
import com.kubekubedashdash.services.metricshistory.jobUsage
import com.kubekubedashdash.services.metricshistory.scheduledSparkUsage
import com.kubekubedashdash.services.metricshistory.sparkUsage
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The demo cluster as the app meets it: a booted mock instance, the real detection and the real
 * in-cluster source over fabric8's HTTP client, and no stand-in for the fake Prometheus.
 */
class DemoPrometheusEndToEndTest {

    private lateinit var handle: MockClusterHandle
    private var manager: KubeConnectionManager? = null
    private var previousCloudClis: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detection = InClusterDetection(scope)
    private val operated = InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = "")

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
        handle = MockClusterProvider.acquireNewInstance()
        // The churn adds and removes pods; the seeded ones the tests read stay, but nothing else needs to move.
        MockClusterProvider.simulators().forEach { it.stop() }
    }

    @AfterTest
    fun tearDown() {
        try {
            shutdownCleanly(scope, label = "DemoPrometheusEndToEndTest", manager = manager)
        } finally {
            // Releasing the last handle stops the simulator and destroys the mock server.
            if (::handle.isInitialized) handle.close()
            val previous = previousCloudClis
            if (previous == null) {
                System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
            } else {
                System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
            }
        }
    }

    private fun source(target: InClusterTarget = operated) = InClusterMetricsSource(
        target = target,
        auto = false,
        clientProvider = { handle.client },
        detection = detection,
        key = "example-context",
        accessReview = { null },
    )

    // A seeded pod with a CPU limit, which the simulator's churn never removes.
    private fun seededPod(): PodRef {
        val pod = assertNotNull(handle.client.pods().inNamespace("default").withName("frontend-7b9d5c8f4-abc12").get())
        return PodRef("default", "frontend-7b9d5c8f4-abc12", ResourceMappers.containerResources(pod))
    }

    @Test
    fun `detection finds the headless Service and skips the agent's decoy`() = runBlocking<Unit> {
        val services = handle.client.services().inNamespace("monitoring")
        assertEquals("true", services.withName("prometheus-operated").get().metadata.labels["operated-prometheus"])
        assertEquals("true", services.withName("prometheus-agent-operated").get().metadata.labels["operated-prometheus"], "the decoy must carry the finder's label")

        val state = PrometheusDetector().detect(handle.client)

        assertEquals(DetectionState.Found(operated), state)
    }

    @Test
    fun `the agent's decoy refuses a query, so the demo exercises the 422 as well as the skip`() = runBlocking<Unit> {
        val result = source(operated.copy(service = "prometheus-agent-operated"))
            .podSeries(PodSeriesKind.Cpu, seededPod(), HistoryRange.M15.window(System.currentTimeMillis() / 1000))

        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "execution"), result)
    }

    @Test
    fun `cpu, memory, network and throttling of a seeded pod are answered with points`() = runBlocking<Unit> {
        val pod = seededPod()
        val containerNames = pod.containers.map { it.name }.sorted()
        assertEquals(listOf("frontend"), containerNames)
        assertTrue(pod.containers.all { it.cpuLimitMillis != null }, "throttling series exist only for containers with a CPU limit")
        val window = HistoryRange.H1.window(System.currentTimeMillis() / 1000)
        val source = source()

        assertEquals("Prometheus · monitoring/prometheus-operated", source.label)
        for (kind in PodSeriesKind.entries) {
            val result = source.podSeries(kind, pod, window)

            assertIs<SeriesResult.Ok>(result, "$kind: $result")
            assertTrue(result.series.isNotEmpty(), "$kind has no series")
            assertTrue(result.series.all { it.points.isNotEmpty() }, "$kind has a series without points")
            val expectedNames = if (kind == PodSeriesKind.NetworkRx || kind == PodSeriesKind.NetworkTx) listOf("") else containerNames
            assertEquals(expectedNames, result.series.map { it.name }, "$kind")
            assertEquals((window.endEpochSec - window.startEpochSec) / window.stepSec + 1, result.series.first().points.size.toLong(), "$kind")
        }
    }

    // The ReplicaSets of the production namespace as the Deployment panel receives them.
    private fun replicaSetRows(): List<GenericResourceInfo> = handle.client.apps().replicaSets().inNamespace("production").list().items.map { rs ->
        GenericResourceInfo(
            uid = rs.metadata.uid.orEmpty(),
            name = rs.metadata.name,
            namespace = rs.metadata.namespace,
            status = null,
            age = "",
            labels = rs.metadata.labels.orEmpty(),
            annotations = rs.metadata.annotations.orEmpty(),
            owners = rs.metadata.ownerReferences.orEmpty().map { OwnerRefInfo(kind = it.kind, name = it.name, uid = it.uid, controller = it.controller == true) },
            creationTimestamp = rs.metadata.creationTimestamp.orEmpty(),
        )
    }

    @Test
    fun `the demo's redis-cache Deployment has two rollouts, a grey earlier pod and a current one`() = runBlocking<Unit> {
        val deployment = assertNotNull(handle.client.apps().deployments().inNamespace("production").withName("redis-cache").get())
        val podSet = assertNotNull(deploymentPodSet(deployment.metadata.uid, "production", replicaSetRows()))
        assertEquals(listOf("redis-cache-4c8d2e6b9", "redis-cache-5f7a3b1d0"), podSet.replicaSets)
        assertEquals("redis-cache-5f7a3b1d0", podSet.currentReplicaSet)
        assertEquals(listOf(1L, 2L), podSet.rollouts.map { it.revision })
        assertEquals(listOf("redis-cache-4c8d2e6b9", "redis-cache-5f7a3b1d0"), podSet.rollouts.map { it.replicaSet })
        val (first, second) = podSet.rollouts
        assertTrue(first.createdEpochSec < second.createdEpochSec)
        val window = HistoryRange.H6.window(System.currentTimeMillis() / 1000)
        assertTrue(first.createdEpochSec > window.startEpochSec, "the first revision must fall inside the 6 h window")
        val source = source()

        for (kind in PodSeriesKind.entries) {
            val result = source.podSetSeries(kind, podSet.ref, PodSetMode.PerPod, window)

            assertIs<SeriesResult.Ok>(result, "$kind: $result")
            assertEquals(listOf("redis-cache-4c8d2e6b9-old01", "redis-cache-5f7a3b1d0-qrs55"), result.series.map { it.name }, "$kind")
            val (old, now) = result.series
            assertTrue(old.points.first().epochSec >= first.createdEpochSec, "$kind: the old revision's line starts before it exists")
            assertTrue(old.points.last().epochSec <= second.createdEpochSec, "$kind: the old revision's line runs past the next rollout")
            assertTrue(now.points.first().epochSec >= second.createdEpochSec, "$kind: the current pod's line starts before its ReplicaSet exists")
            assertEquals(window.endEpochSec, now.points.last().epochSec, "$kind")
        }
        val total = source.podSetSeries(PodSeriesKind.Memory, podSet.ref, PodSetMode.Total, window)
        assertIs<SeriesResult.Ok>(total)
        assertEquals(listOf(""), total.series.map { it.name })
        assertTrue(total.series.single().points.isNotEmpty())
    }

    @Test
    fun `a scope's line ends on the usage the Overview gauges add up from PodMetrics`() = runBlocking<Unit> {
        // team-a is not a demo namespace, so the simulator never writes PodMetrics there.
        val mib = 1024L * 1024
        handle.client.resource(MockClusterProvider.buildPodMetrics("web-0", "team-a", listOf(Triple("app", 250L, 300 * mib), Triple("sidecar", 50L, 20 * mib)))).create()
        handle.client.resource(MockClusterProvider.buildPodMetrics("web-1", "team-a", listOf(Triple("app", 100L, 200 * mib)))).create()
        val gauges = handle.client.top().pods().inNamespace("team-a").metrics().items.flatMap { it.containers }
        val expected = mapOf(
            ScopeKind.CpuCores to gauges.sumOf { it.usage.getValue("cpu").numericalAmount.toDouble() },
            ScopeKind.MemoryBytes to gauges.sumOf { it.usage.getValue("memory").numericalAmount.toDouble() },
        )
        assertEquals(0.4, expected.getValue(ScopeKind.CpuCores), 1e-9)
        val window = HistoryRange.H6.window(System.currentTimeMillis() / 1000)
        val source = source()

        for (kind in ScopeKind.entries) {
            val result = source.scopeSeries(kind, ScopeRef(setOf("team-a")), window)

            assertIs<SeriesResult.Ok>(result, "$kind: $result")
            val line = result.series.single()
            assertEquals("", line.name, "$kind")
            assertEquals((window.endEpochSec - window.startEpochSec) / window.stepSec + 1, line.points.size.toLong(), "$kind")
            assertTrue(line.points.all { it.value > 0 }, "$kind")
            assertEquals(expected.getValue(kind), line.points.last().value, 1e-6 * expected.getValue(kind), "$kind: the right edge is the gauges' sum")
        }
    }

    @Test
    fun `a pod that is not in the cluster reads as no history, not as a failure`() = runBlocking<Unit> {
        val result = source().podSeries(PodSeriesKind.Cpu, PodRef("default", "gone-0"), HistoryRange.M15.window(System.currentTimeMillis() / 1000))

        assertEquals(SeriesResult.Ok(emptyList()), result)
    }

    @Test
    fun `a service the demo does not have is reported as gone`() = runBlocking<Unit> {
        val result = source(operated.copy(service = "prometheus-server")).podSeries(PodSeriesKind.Cpu, seededPod(), HistoryRange.M15.window(System.currentTimeMillis() / 1000))

        assertEquals(SeriesResult.Failed(HistoryFailure.ServiceGone), result)
    }

    @Test
    fun `the release check helper reads CPU points from the demo`() {
        assertTrue(ReleaseCheckSupport.demoCpuHistoryPoints(handle.client) > 0)
    }

    // ── Jobs, CronJobs and Spark applications ───────────────────────────────

    // A Job as the Jobs table receives it: the times of its run are filled the way the app fills them.
    private fun jobRow(job: Job): GenericResourceInfo = GenericResourceInfo(
        uid = job.metadata.uid.orEmpty(),
        name = job.metadata.name,
        namespace = job.metadata.namespace,
        status = null,
        age = "",
        labels = job.metadata.labels.orEmpty(),
        annotations = job.metadata.annotations.orEmpty(),
        owners = job.metadata.ownerReferences.orEmpty().map { OwnerRefInfo(kind = it.kind, name = it.name, uid = it.uid, controller = it.controller == true) },
        creationTimestamp = job.metadata.creationTimestamp.orEmpty(),
        run = ResourceMappers.mapJobRun(job),
    )

    private fun jobRows(namespace: String): List<GenericResourceInfo> = handle.client.batch().v1().jobs().inNamespace(namespace).list().items.map(::jobRow)

    // The rows of a Spark operator kind in a namespace, mapped the way the custom resource screens map them.
    private fun sparkRows(kind: String, namespace: String): List<GenericResourceInfo> {
        val crd = assertNotNull(
            handle.client.apiextensions().v1().customResourceDefinitions().list().items.mapNotNull { ResourceMappers.mapCrd(it) }.firstOrNull { it.key == "sparkoperator.k8s.io/$kind" },
        )
        val context = ResourceDefinitionContext.Builder()
            .withGroup(crd.group).withVersion(crd.version).withKind(crd.kind).withPlural(crd.plural).withNamespaced(crd.namespaced)
            .build()
        return handle.client.genericKubernetesResources(context).inNamespace(namespace).list().items.map { ResourceMappers.mapCrInstance(it, crd) }
    }

    private fun epochSec(timestamp: String) = Instant.parse(timestamp).epochSecond

    @Test
    fun `the finished db-vacuum Job is drawn as its one pod, from its start to its end`() = runBlocking<Unit> {
        val row = assertNotNull(jobRows("production").firstOrNull { it.name == "db-vacuum-28000002" })
        val usage = assertNotNull(jobUsage(row))
        val start = assertNotNull(usage.runStartSec)
        val end = assertNotNull(usage.runEndSec)
        assertTrue(end - start in 539L..541L, "the Job ran for nine minutes: ${end - start} s")
        val window = QueryWindow.spanning(start, end)

        for (kind in PodSeriesKind.entries) {
            val result = source().podSetSeries(kind, assertNotNull(usage.ref), PodSetMode.PerPod, window)

            assertIs<SeriesResult.Ok>(result, "$kind: $result")
            val line = result.series.single()
            assertEquals("db-vacuum-28000002-k4m7p", line.name, "$kind")
            assertTrue(line.points.isNotEmpty(), "$kind")
            assertTrue(line.points.all { it.epochSec in start..end }, "$kind: the line leaves the pod's run")
            assertTrue(line.points.first().epochSec < start + window.stepSec, "$kind: the line starts at the pod's creation")
            assertTrue(line.points.last().epochSec > end - window.stepSec, "$kind: the line runs to the pod's end")
        }
    }

    @Test
    fun `a running seeded Job has a pod that is drawn to the window's end`() = runBlocking<Unit> {
        val row = assertNotNull(jobRows("production").firstOrNull { it.name == "nightly-backup-28000001" })
        val usage = assertNotNull(jobUsage(row))
        assertNull(usage.runEndSec, "the Job is still running")
        val window = HistoryRange.H1.window(System.currentTimeMillis() / 1000)

        val result = source().podSetSeries(PodSeriesKind.Cpu, assertNotNull(usage.ref), PodSetMode.PerPod, window)

        assertIs<SeriesResult.Ok>(result)
        val line = result.series.single()
        assertEquals("nightly-backup-28000001-r9q4z", line.name)
        assertEquals(window.endEpochSec, line.points.last().epochSec)
        assertTrue(line.points.first().epochSec >= epochSec(row.creationTimestamp), "the line starts before the pod exists")
    }

    @Test
    fun `the db-vacuum CronJob draws the pod of its finished run`() = runBlocking<Unit> {
        val cron = assertNotNull(handle.client.batch().v1().cronjobs().inNamespace("production").withName("db-vacuum").get())
        val cronRow = GenericResourceInfo(
            uid = cron.metadata.uid.orEmpty(),
            name = cron.metadata.name,
            namespace = cron.metadata.namespace,
            status = null,
            age = "",
            labels = emptyMap(),
            annotations = emptyMap(),
        )
        val usage = assertNotNull(cronJobUsage(cronRow, jobRows("production"), ZoneOffset.UTC))
        assertTrue(usage.markers.isNotEmpty(), "the finished run is a marker")

        val result = source().podSetSeries(PodSeriesKind.Memory, assertNotNull(usage.ref), PodSetMode.PerPod, HistoryRange.H6.window(System.currentTimeMillis() / 1000))

        assertIs<SeriesResult.Ok>(result)
        assertTrue("db-vacuum-28000002-k4m7p" in result.series.map { it.name }, result.series.map { it.name }.toString())
    }

    @Test
    fun `the seeded SparkApplications draw their driver and executors, the finished one up to its end`() = runBlocking<Unit> {
        val running = assertNotNull(sparkRows("SparkApplication", "default").firstOrNull { it.name == "spark-pi-success" })
        val runningUsage = assertNotNull(sparkUsage(running))
        val now = HistoryRange.H1.window(System.currentTimeMillis() / 1000)
        val liveResult = source().podSetSeries(PodSeriesKind.Cpu, assertNotNull(runningUsage.ref), PodSetMode.PerPod, now)
        assertIs<SeriesResult.Ok>(liveResult)
        assertEquals(listOf("spark-pi-success-driver", "spark-pi-success-exec-1", "spark-pi-success-exec-2"), liveResult.series.map { it.name })
        assertTrue(liveResult.series.all { it.points.last().epochSec == now.endEpochSec }, "the pods are still running")

        val finished = assertNotNull(sparkRows("SparkApplication", "production").firstOrNull { it.name == "spark-etl-nightly" })
        val usage = assertNotNull(sparkUsage(finished))
        val start = assertNotNull(usage.runStartSec)
        val end = assertNotNull(usage.runEndSec)
        assertTrue(end - start in 3_599L..3_601L, "the application ran for an hour: ${end - start} s")
        val window = QueryWindow.spanning(start, end)
        val result = source().podSetSeries(PodSeriesKind.Memory, assertNotNull(usage.ref), PodSetMode.PerPod, window)

        assertIs<SeriesResult.Ok>(result)
        assertEquals(listOf("spark-etl-nightly-driver") + (1..3).map { "spark-etl-nightly-exec-$it" }, result.series.map { it.name })
        assertTrue(result.series.all { s -> s.points.all { it.epochSec in start..end } }, "a line leaves the run")
    }

    @Test
    fun `the seeded ScheduledSparkApplication draws the pods of both its runs`() = runBlocking<Unit> {
        val scheduled = assertNotNull(sparkRows("ScheduledSparkApplication", "production").firstOrNull { it.name == "spark-report-hourly" })
        val runs = sparkRows("SparkApplication", "production")
        val usage = assertNotNull(scheduledSparkUsage(scheduled, runs, ZoneOffset.UTC))
        assertEquals(2, usage.markers.size)
        val start = assertNotNull(usage.runStartSec)
        val window = QueryWindow.spanning(start, System.currentTimeMillis() / 1000)

        val result = source().podSetSeries(PodSeriesKind.Cpu, assertNotNull(usage.ref), PodSetMode.PerPod, window)

        assertIs<SeriesResult.Ok>(result)
        val runNames = listOf("spark-report-hourly-1760000000000000000", "spark-report-hourly-1760003600000000000")
        val expectedNames = runNames.flatMap { listOf("$it-driver", "$it-exec-1", "$it-exec-2") }.sorted()
        assertEquals(expectedNames, result.series.map { it.name })
        assertTrue(result.series.all { it.points.isNotEmpty() })
    }

    @Test
    fun `the seeded Jobs, Spark applications and their pods have the ages they are built with`() {
        fun ageMinutes(timestamp: String) = (System.currentTimeMillis() / 1000 - epochSec(timestamp)) / 60
        val client = handle.client
        val pods = client.pods().inNamespace("production").list().items.associateBy { it.metadata.name } + client.pods().inNamespace("default").list().items.associateBy { it.metadata.name }
        val jobs = client.batch().v1().jobs().inNamespace("production").list().items.associateBy { it.metadata.name }
        val spark = sparkRows("SparkApplication", "production") + sparkRows("SparkApplication", "default") + sparkRows("ScheduledSparkApplication", "production")
        val ages = mapOf(
            "db-vacuum-28000002" to (ageMinutes(jobs.getValue("db-vacuum-28000002").metadata.creationTimestamp) to 50L),
            "db-vacuum-28000002-k4m7p" to (ageMinutes(pods.getValue("db-vacuum-28000002-k4m7p").metadata.creationTimestamp) to 50L),
            "nightly-backup-28000001" to (ageMinutes(jobs.getValue("nightly-backup-28000001").metadata.creationTimestamp) to 15L),
            "nightly-backup-28000001-r9q4z" to (ageMinutes(pods.getValue("nightly-backup-28000001-r9q4z").metadata.creationTimestamp) to 15L),
            "spark-pi-success-driver" to (ageMinutes(pods.getValue("spark-pi-success-driver").metadata.creationTimestamp) to 20L),
            "spark-etl-nightly-exec-3" to (ageMinutes(pods.getValue("spark-etl-nightly-exec-3").metadata.creationTimestamp) to 176L),
            "spark-pi-success" to (ageMinutes(spark.single { it.name == "spark-pi-success" }.creationTimestamp) to 25L),
            "spark-etl-nightly" to (ageMinutes(spark.single { it.name == "spark-etl-nightly" }.creationTimestamp) to 180L),
            "spark-report-hourly-1760003600000000000" to (ageMinutes(spark.single { it.name == "spark-report-hourly-1760003600000000000" }.creationTimestamp) to 70L),
        )
        for ((name, age) in ages) {
            assertTrue(age.first in (age.second - 1)..(age.second + 1), "$name is ${age.first} minutes old, expected ${age.second}")
        }
    }

    @Test
    fun `the seeded Spark pods hang off their application the way the operator's do, and the scheduled runs off their ScheduledSparkApplication`() {
        val scheduled = assertNotNull(sparkRows("ScheduledSparkApplication", "production").firstOrNull { it.name == "spark-report-hourly" })
        assertTrue(scheduled.uid.isNotBlank())
        assertEquals("@every 1h", scheduled.extraColumns["Schedule"])
        assertTrue(scheduled.extraColumns.getValue("Last run").isNotBlank())
        val apps = sparkRows("SparkApplication", "production")
        val runs = apps.filter { it.name.startsWith("spark-report-hourly-") }
        assertEquals(2, runs.size)
        val pods = handle.client.pods().inNamespace("production").list().items

        for (app in apps.filter { it.name == "spark-etl-nightly" } + runs) {
            if (app in runs) {
                assertTrue(app.owners.any { it.uid == scheduled.uid && it.kind == "ScheduledSparkApplication" && it.controller }, app.name)
                assertEquals("spark-report-hourly", app.labels["sparkoperator.k8s.io/scheduled-app-name"], app.name)
            }
            val driver = pods.single { it.metadata.name == "${app.name}-driver" }
            assertEquals(listOf(app.uid), driver.metadata.ownerReferences.map { it.uid }, app.name)
            assertEquals("driver", driver.metadata.labels["spark-role"], app.name)
            assertEquals("Succeeded", driver.status.phase, app.name)
            val executors = pods.filter { it.metadata.name.startsWith("${app.name}-exec-") }
            assertEquals(app.spark?.executorPods?.size, executors.size, app.name)
            assertEquals(app.spark?.executorPods, executors.map { it.metadata.name }.sorted(), app.name)
            assertTrue(executors.all { it.metadata.ownerReferences.single().uid == driver.metadata.uid && it.metadata.labels["spark-role"] == "executor" }, app.name)
        }
    }

    // ── the app's wiring ────────────────────────────────────────────────────

    /**
     * A tab's connection manager attached to the demo instance, and the service built the way
     * ReactiveKubeClient builds it: the live context and the client both come from the manager.
     */
    private fun tabService(): Pair<KubeConnectionManager, MetricsHistoryService> {
        val tab = KubeConnectionManager()
        manager = tab
        // A second handle on the running instance, owned by the manager from here on.
        assertTrue(tab.connectWithMockHandle(MockClusterProvider.acquire(handle.label)).isSuccess)
        val service = MetricsHistoryService(
            currentContext = { tab.connectedContextOrNull() },
            settingsFlow = { MutableStateFlow<Map<String, MetricsSourceSetting>>(emptyMap()) },
            gmpSourceFor = { error("the demo is not a gke context") },
            detection = detection,
            clientFor = { context -> withContext(Dispatchers.IO) { tab.clientIfConnectedTo(context) } },
        )
        return tab to service
    }

    @Test
    fun `a tab connected to the demo has a client for the context it reports`() {
        val (tab, _) = tabService()

        val context = assertNotNull(tab.connectedContextOrNull())

        assertEquals(handle.label, context)
        assertTrue(DemoContext.isMockContext(context))
        assertSame(tab.client, tab.clientIfConnectedTo(context))
        assertNull(tab.clientIfConnectedTo("example-context"), "another cluster's name must not get the demo's client")
    }

    @Test
    fun `the demo resolves through detection to its Prometheus and reads history from it`() = runBlocking<Unit> {
        val (_, service) = tabService()
        assertEquals(SourceChoice.Detecting, service.choice())

        service.ensureDetected()

        val choice = service.choice()
        assertEquals(SourceChoice.InCluster(operated, auto = true), choice)
        val source = assertNotNull(service.sourceFor(choice))
        assertEquals("Prometheus · monitoring/prometheus-operated", source.label)
        val result = source.podSeries(PodSeriesKind.Memory, seededPod(), HistoryRange.M15.window(System.currentTimeMillis() / 1000))
        assertIs<SeriesResult.Ok>(result)
        assertEquals(listOf("frontend"), result.series.map { it.name })
        assertTrue(result.series.single().points.isNotEmpty())
    }

    @Test
    fun `turning the demo's history off wins over detection`() = runBlocking<Unit> {
        val (tab, _) = tabService()
        val off = MetricsHistoryService(
            currentContext = { tab.connectedContextOrNull() },
            settingsFlow = { MutableStateFlow(mapOf(DemoContext.MOCK_CONTEXT_NAME to MetricsSourceSetting(mode = MetricsSourceMode.OFF))) },
            gmpSourceFor = { error("the demo is not a gke context") },
            detection = detection,
            clientFor = { context -> withContext(Dispatchers.IO) { tab.clientIfConnectedTo(context) } },
        )

        assertEquals(SourceChoice.None(NoSourceReason.Disabled), off.choice())
    }
}
