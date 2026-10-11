package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.ShellEnvironment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which source answers for a context: Off beats everything, the demo is never GMP, and every field is validated. */
class ResolveMetricsSourceTest {

    private var previousCloudClis: String? = null

    @BeforeTest
    fun disableCloudClis() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun restoreCloudClis() {
        val previous = previousCloudClis
        if (previous == null) {
            System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        } else {
            System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
        }
    }

    private val gkeContext = "gke_example-project_us-central1_example-cluster"
    private val triple = GmpTarget("example-project", "us-central1", "example-cluster")
    private val found = InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = "")

    private fun resolve(
        context: String?,
        isDemo: Boolean = false,
        settings: Map<String, MetricsSourceSetting> = emptyMap(),
        detection: DetectionState? = null,
    ) = resolveMetricsSource(context, isDemo, settings, detection)

    private fun gmp(project: String = "example-project", location: String = "us-central1", cluster: String = "example-cluster") = MetricsSourceSetting(MetricsSourceMode.GMP, project, location, cluster)

    private fun inCluster(
        namespace: String = "monitoring",
        service: String = "prometheus-server",
        port: String = "80",
        pathPrefix: String = "",
        https: Boolean = false,
    ) = MetricsSourceSetting(MetricsSourceMode.IN_CLUSTER, namespace = namespace, service = service, port = port, pathPrefix = pathPrefix, https = https)

    @Test
    fun `no live context is not connected`() {
        assertEquals(SourceChoice.None(NoSourceReason.NotConnected), resolve(null))
        assertEquals(SourceChoice.None(NoSourceReason.NotConnected), resolve(null, detection = DetectionState.Found(found)))
    }

    @Test
    fun `auto reads the project, location and cluster from a gke context name`() {
        assertEquals(SourceChoice.Gmp(triple, auto = true), resolve(gkeContext))
    }

    @Test
    fun `auto keeps Managed Prometheus on a gke context even when an in-cluster Prometheus was found`() {
        assertEquals(SourceChoice.Gmp(triple, auto = true), resolve(gkeContext, detection = DetectionState.Found(found)))
        assertEquals(SourceChoice.Gmp(triple, auto = true), resolve(gkeContext, detection = DetectionState.NothingFound(atMs = 42)))
    }

    @Test
    fun `auto on a context that is not a gke name waits for the detection`() {
        assertEquals(SourceChoice.Detecting, resolve("example-context"))
        assertEquals(SourceChoice.Detecting, resolve("example-context", detection = DetectionState.Searching))
        assertEquals(SourceChoice.Detecting, resolve("gke_example-project_us-central1"))
    }

    @Test
    fun `auto on a context that is not a gke name follows the detection result`() {
        assertEquals(SourceChoice.InCluster(found, auto = true), resolve("example-context", detection = DetectionState.Found(found)))
        assertEquals(SourceChoice.None(NoSourceReason.NothingFound), resolve("example-context", detection = DetectionState.NothingFound(atMs = 42)))
        assertEquals(SourceChoice.None(NoSourceReason.CannotSearch), resolve("example-context", detection = DetectionState.CannotSearch(atMs = 42)))
        assertEquals(SourceChoice.None(NoSourceReason.SearchFailed), resolve("example-context", detection = DetectionState.SearchFailed(atMs = 42)))
    }

    @Test
    fun `auto treats a gke context whose parts fail the import validators like any other context`() {
        assertEquals(SourceChoice.Detecting, resolve("gke_example-project_us-central1_Bad"))
        assertEquals(SourceChoice.Detecting, resolve("gke_Bad Project_us-central1_example-cluster"))
        assertEquals(
            SourceChoice.InCluster(found, auto = true),
            resolve("gke_example-project_us-central1_Bad", detection = DetectionState.Found(found)),
        )
    }

    @Test
    fun `a manual target on any context is used as typed, trimmed`() {
        val settings = mapOf("example-context" to gmp(project = " example-project ", location = "us-central1\n", cluster = " example-cluster"))

        assertEquals(SourceChoice.Gmp(triple, auto = false), resolve("example-context", settings = settings))
    }

    @Test
    fun `a manual target wins over what the context name says`() {
        val settings = mapOf(gkeContext to gmp(project = "other-project"))

        assertEquals(
            SourceChoice.Gmp(GmpTarget("other-project", "us-central1", "example-cluster"), auto = false),
            resolve(gkeContext, settings = settings),
        )
    }

    @Test
    fun `a manual target with an invalid field is incomplete`() {
        for (setting in listOf(gmp(project = "Bad Project"), gmp(location = ""), gmp(cluster = "a\"b"), gmp(project = "example-project\" or 1"))) {
            assertEquals(
                SourceChoice.None(NoSourceReason.IncompleteSetting),
                resolve("example-context", settings = mapOf("example-context" to setting)),
                "$setting",
            )
        }
    }

    @Test
    fun `an in-cluster setting is used as typed, trimmed, whatever the detection says`() {
        val settings = mapOf("example-context" to inCluster(namespace = " monitoring ", service = "prometheus-server\n", port = " 80", pathPrefix = " /prom/ ", https = true))
        val expected = InClusterTarget("monitoring", "prometheus-server", "80", https = true, pathPrefix = "/prom")

        assertEquals(SourceChoice.InCluster(expected, auto = false), resolve("example-context", settings = settings))
        assertEquals(SourceChoice.InCluster(expected, auto = false), resolve("example-context", settings = settings, detection = DetectionState.NothingFound(atMs = 42)))
        assertEquals(SourceChoice.InCluster(expected, auto = false), resolve("example-context", settings = settings, detection = DetectionState.Found(found)))
    }

    @Test
    fun `an in-cluster setting wins over the gke context name`() {
        val settings = mapOf(gkeContext to inCluster(port = "web"))

        assertEquals(
            SourceChoice.InCluster(InClusterTarget("monitoring", "prometheus-server", "web", https = false, pathPrefix = ""), auto = false),
            resolve(gkeContext, settings = settings),
        )
    }

    @Test
    fun `an in-cluster setting with an invalid field is incomplete`() {
        val bad = listOf(
            inCluster(namespace = ""),
            inCluster(namespace = "Monitoring"),
            inCluster(namespace = "monitoring/../kube-system"),
            inCluster(service = ""),
            inCluster(service = "prometheus-server:80"),
            inCluster(port = ""),
            inCluster(port = "0"),
            inCluster(port = "65536"),
            inCluster(port = "8080/proxy"),
            inCluster(pathPrefix = "prom"),
            inCluster(pathPrefix = "/prom?x=1"),
            inCluster(pathPrefix = "/../kube-system"),
            inCluster(pathPrefix = "/prom//"),
        )
        for (setting in bad) {
            assertEquals(
                SourceChoice.None(NoSourceReason.IncompleteSetting),
                resolve("example-context", settings = mapOf("example-context" to setting), detection = DetectionState.Found(found)),
                "$setting",
            )
        }
    }

    @Test
    fun `an Auto setting that keeps in-cluster fields still follows the detection`() {
        val settings = mapOf("example-context" to inCluster().copy(mode = MetricsSourceMode.AUTO))

        assertEquals(SourceChoice.Detecting, resolve("example-context", settings = settings))
        assertEquals(SourceChoice.InCluster(found, auto = true), resolve("example-context", settings = settings, detection = DetectionState.Found(found)))
    }

    @Test
    fun `off disables a gke context and a detected one`() {
        val settings = mapOf(gkeContext to MetricsSourceSetting(MetricsSourceMode.OFF), "example-context" to MetricsSourceSetting(MetricsSourceMode.OFF))

        assertEquals(SourceChoice.None(NoSourceReason.Disabled), resolve(gkeContext, settings = settings))
        assertEquals(SourceChoice.None(NoSourceReason.Disabled), resolve("example-context", settings = settings, detection = DetectionState.Found(found)))
    }

    @Test
    fun `a demo context is the demo source`() {
        assertEquals(SourceChoice.Demo, resolve("demo-cluster (mock) #2"))
        assertEquals(SourceChoice.Demo, resolve(DemoContext.MOCK_CONTEXT_NAME))
    }

    @Test
    fun `a connection that is the demo is the demo source whatever the context is called`() {
        assertEquals(SourceChoice.Demo, resolve("example-context", isDemo = true))
        assertEquals(SourceChoice.Demo, resolve("example-context", isDemo = true, detection = DetectionState.Found(found)))
    }

    @Test
    fun `a manual target does not turn the demo into Managed Prometheus or an in-cluster source`() {
        val settings = mapOf(DemoContext.MOCK_CONTEXT_NAME to gmp())
        val inClusterSettings = mapOf(DemoContext.MOCK_CONTEXT_NAME to inCluster())

        assertEquals(SourceChoice.Demo, resolve("demo-cluster (mock) #2", isDemo = true, settings = settings))
        assertEquals(SourceChoice.Demo, resolve("demo-cluster (mock) #2", isDemo = true, settings = inClusterSettings))
    }

    @Test
    fun `off stored under the demo row applies to every minted demo label`() {
        val settings = mapOf(DemoContext.MOCK_CONTEXT_NAME to MetricsSourceSetting(MetricsSourceMode.OFF))

        assertEquals(SourceChoice.None(NoSourceReason.Disabled), resolve("demo-cluster (mock) #2", isDemo = true, settings = settings))
        assertEquals(SourceChoice.None(NoSourceReason.Disabled), resolve(DemoContext.MOCK_CONTEXT_NAME, isDemo = true, settings = settings))
    }

    @Test
    fun `a setting stored for another cluster does not apply`() {
        val settings = mapOf("example-context" to MetricsSourceSetting(MetricsSourceMode.OFF))

        assertEquals(SourceChoice.Gmp(triple, auto = true), resolve(gkeContext, settings = settings))
        assertEquals(SourceChoice.Detecting, resolve("example-context-2", settings = settings))
    }
}
