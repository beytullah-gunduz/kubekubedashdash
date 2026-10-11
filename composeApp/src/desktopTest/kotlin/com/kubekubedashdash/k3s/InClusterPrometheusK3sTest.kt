package com.kubekubedashdash.k3s

import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.InClusterMetricsSource
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PrometheusDetector
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.ServiceProxyAccessReview
import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.shutdownCleanly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The in-cluster Prometheus source against a real Prometheus in a throwaway K3s cluster
 * (Testcontainers), reached through the real service proxy. Opt-in, never part of `desktopTest` or
 * CI: it needs a reachable Docker and takes several minutes (the first run also pulls three images).
 *
 * ```
 * ./gradlew :composeApp:k3sIntegrationTest   the checks below, then the cluster is removed
 * ./gradlew :composeApp:k3sDevCluster        the same cluster, kept up for a live smoke of the app
 * ```
 * The README section "Integration tests (K3s)" has the Colima settings. Each test method does nothing
 * unless its Gradle task set its property, so an IDE run or `desktopTest --tests` cannot start a
 * container; with the property set and Docker unreachable the test FAILS, it never skips.
 *
 * The cluster (`resources/k3s`): `monitoring/prometheus-server` (the prometheus chart's Service labels,
 * port `http` 80 to 9090, a 5 s cAdvisor scrape through the API server) and the pod `team-a/web-0`
 * with `app` (CPU limit 500m, bursts past it) and `sidecar` (no limit). Both clients are built from the
 * cluster's own credentials with auto-configuration off; your kubeconfig is never read.
 *
 * What [inClusterPrometheusEndToEnd] checks, in order (the deleting steps are last):
 * - detection finds the Service (rank 2) with port `http`;
 * - the source answers `Ok` and non-empty for every kind over 15 minutes: CPU and memory per
 *   container, throttling for `app` only, network in both directions;
 * - as `viewer` (the built-in `view` role in `team-a` only): the access review says no, the source
 *   reports NoProxyAccess, and detection reports CannotSearch (or NothingFound; the outcome is printed);
 * - a wrong path prefix gives WrongPath; a deleted Service gives ServiceGone and drops the detected target.
 */
class InClusterPrometheusK3sTest {
    companion object {
        private val lock = Any()
        private var cluster: K3sCluster? = null

        private const val ADMIN_KEY = "k3s-admin"
        private const val VIEWER_KEY = "k3s-viewer"
        private const val DEV_MAX_MIN = 60L

        /** The one cluster of this class's run: started by the first test method that needs it. */
        private fun cluster(): K3sCluster = synchronized(lock) { cluster ?: K3sCluster.start().also { cluster = it } }

        @JvmStatic
        @AfterClass
        fun stopCluster() {
            synchronized(lock) {
                cluster?.close()
                cluster = null
            }
        }
    }

    private var previousCloudClis: String? = null

    // Nothing here runs aws or gcloud; the property keeps it that way for any code path that would look them up.
    @Before
    fun disableCloudClis() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @After
    fun restoreCloudClis() {
        val previous = previousCloudClis
        if (previous == null) {
            System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        } else {
            System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
        }
    }

    @Test
    fun inClusterPrometheusEndToEnd() {
        Assume.assumeTrue("run it with ./gradlew :composeApp:k3sIntegrationTest", System.getProperty("kkdd.k3s") == "true")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val cluster = cluster()
            runBlocking { endToEnd(cluster, InClusterDetection(scope)) }
        } finally {
            shutdownCleanly(scope, label = "InClusterPrometheusK3sTest")
        }
    }

    /**
     * Starts the cluster, writes `kubeconfig-admin.yaml`, `kubeconfig-viewer.yaml` and
     * `kubeconfig-viewer-ns.yaml` into the `k3s` directory of the module's build directory, prints
     * `K3S READY`, and keeps the cluster up until `stop` exists next to them (or 60 minutes after
     * the start of this method).
     */
    @Test
    fun devCluster() {
        Assume.assumeTrue("run it with ./gradlew :composeApp:k3sDevCluster", System.getProperty("kkdd.k3s.dev") == "true")
        val dir = File(System.getProperty("kkdd.k3s.dir") ?: fail("kkdd.k3s.dir is not set: run it with ./gradlew :composeApp:k3sDevCluster"))
        val startedAt = System.nanoTime()
        val stop = File(dir, "stop")
        // A stop file from an earlier run would end this one at once.
        dir.mkdirs()
        stop.delete()
        val cluster = cluster()
        runBlocking { k3sStep("wait for the workload's metrics") { cluster.awaitWorkloadMetrics() } }
        val files = cluster.writeKubeconfigs(dir)
        // Relative to the module, like the Gradle properties that take them (-PhotRunKubeconfig=build/k3s/...).
        files.forEach { k3sSay("wrote ${dir.parentFile.name}/${dir.name}/${it.name}") }
        println("K3S READY")
        System.out.flush()
        k3sSay("stopping when ${dir.parentFile.name}/${dir.name}/${stop.name} exists, or after $DEV_MAX_MIN minutes")
        val deadline = startedAt + DEV_MAX_MIN * 60_000_000_000
        while (!stop.exists() && System.nanoTime() < deadline) Thread.sleep(1_000)
        k3sSay(if (stop.exists()) "stop file found" else "time is up")
    }

    private suspend fun endToEnd(cluster: K3sCluster, detection: InClusterDetection) {
        val admin = cluster.admin
        val viewer = cluster.viewer
        val target = K3sCluster.PROMETHEUS_TARGET
        val pod = PodRef(K3sCluster.WORKLOAD_NAMESPACE, K3sCluster.WORKLOAD_POD)

        k3sStep("wait for the workload's metrics") { cluster.awaitWorkloadMetrics() }

        k3sStep("detection: the Service, found by the chart's labels, with its port name") {
            assertEquals(DetectionState.Found(target), PrometheusDetector().detect(admin), "PrometheusDetector on the admin's view")
            detection.ensure(ADMIN_KEY) { admin }
            assertEquals(DetectionState.Found(target), detection.current(ADMIN_KEY), "InClusterDetection state of the admin cluster")
        }

        val adminReview = ServiceProxyAccessReview()
        fun adminSource(at: InClusterTarget) = InClusterMetricsSource(
            target = at,
            auto = true,
            clientProvider = { admin },
            detection = detection,
            key = ADMIN_KEY,
            accessReview = { namespace -> adminReview.allowed(ADMIN_KEY, namespace) { admin } },
        )
        val window = HistoryRange.M15.window(Instant.now().epochSecond)

        k3sStep("admin: every kind over 15 minutes") {
            val source = adminSource(target)
            assertEquals("Prometheus · monitoring/prometheus-server", source.label)
            suspend fun series(kind: PodSeriesKind): List<Series> = okSeries(kind, source.podSeries(kind, pod, window))

            for (kind in listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory)) {
                val found = series(kind)
                assertTrue(found.map { it.name }.containsAll(listOf("app", "sidecar")), "$kind: series for app and sidecar, got ${found.map { it.name }}")
                assertTrue(found.all { it.points.isNotEmpty() }, "$kind: every series has points")
            }
            for (kind in listOf(PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx)) {
                val found = series(kind)
                assertTrue(found.isNotEmpty() && found.all { it.points.isNotEmpty() }, "$kind: one series with points, got ${found.size}")
            }
            // A container without a CPU limit has no CFS periods, so the division drops out and it has no series.
            assertEquals(listOf("app"), series(PodSeriesKind.Throttling).map { it.name }, "Throttling: only the container with a CPU limit")
        }

        k3sStep("viewer: no proxy access, and detection cannot search") {
            val review = ServiceProxyAccessReview()
            assertEquals(false, review.allowed(VIEWER_KEY, target.namespace) { viewer }, "SelfSubjectAccessReview of get services/proxy in the Prometheus namespace")
            val source = InClusterMetricsSource(
                target = target,
                auto = false,
                clientProvider = { viewer },
                detection = detection,
                key = VIEWER_KEY,
                accessReview = { namespace -> review.allowed(VIEWER_KEY, namespace) { viewer } },
            )
            assertEquals(SeriesResult.Failed(HistoryFailure.NoProxyAccess), source.podSeries(PodSeriesKind.Cpu, pod, window), "the viewer's history query")

            val searched = PrometheusDetector().detect(viewer)
            assertTrue(searched is DetectionState.CannotSearch || searched is DetectionState.NothingFound, "the viewer's detection must be CannotSearch or NothingFound, got ${searched::class.simpleName}")
            k3sSay("viewer detection outcome: ${searched::class.simpleName}")
        }

        k3sStep("a wrong path prefix is WrongPath") {
            val wrong = adminSource(target.copy(pathPrefix = "/nope")).podSeries(PodSeriesKind.Cpu, pod, window)
            assertEquals(SeriesResult.Failed(HistoryFailure.WrongPath), wrong, "a path prefix Prometheus does not serve")
        }

        k3sStep("a deleted Service is ServiceGone, and the detected target is dropped") {
            admin.services().inNamespace(target.namespace).withName(target.service).delete()
            val gone = adminSource(target).podSeries(PodSeriesKind.Cpu, pod, window)
            assertEquals(SeriesResult.Failed(HistoryFailure.ServiceGone), gone, "the query after the Service was deleted")
            assertNull(detection.current(ADMIN_KEY), "a vanished Service sends the cluster's search back to the start")
        }
    }

    // A failure's text is a fixed phrase, an HTTP status or an errorType, so it can go into a message.
    private fun okSeries(kind: PodSeriesKind, result: SeriesResult): List<Series> = (result as? SeriesResult.Ok)?.series ?: fail("$kind: expected Ok, got $result")
}
