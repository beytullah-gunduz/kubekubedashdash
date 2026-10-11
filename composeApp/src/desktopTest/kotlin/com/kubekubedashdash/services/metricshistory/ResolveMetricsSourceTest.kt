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

    private fun gmp(project: String = "example-project", location: String = "us-central1", cluster: String = "example-cluster") = MetricsSourceSetting(MetricsSourceMode.GMP, project, location, cluster)

    @Test
    fun `no live context is not connected`() {
        assertEquals(SourceChoice.None(NoSourceReason.NotConnected), resolveMetricsSource(null, isDemo = false, settings = emptyMap()))
    }

    @Test
    fun `auto reads the project, location and cluster from a gke context name`() {
        assertEquals(SourceChoice.Gmp(triple, auto = true), resolveMetricsSource(gkeContext, isDemo = false, settings = emptyMap()))
    }

    @Test
    fun `auto does not guess for a context that is not a gke name`() {
        assertEquals(SourceChoice.None(NoSourceReason.NotGkeContext), resolveMetricsSource("example-context", isDemo = false, settings = emptyMap()))
        assertEquals(
            SourceChoice.None(NoSourceReason.NotGkeContext),
            resolveMetricsSource("gke_example-project_us-central1", isDemo = false, settings = emptyMap()),
        )
    }

    @Test
    fun `auto rejects a gke context whose parts fail the import validators`() {
        assertEquals(
            SourceChoice.None(NoSourceReason.NotGkeContext),
            resolveMetricsSource("gke_example-project_us-central1_Bad", isDemo = false, settings = emptyMap()),
        )
        assertEquals(
            SourceChoice.None(NoSourceReason.NotGkeContext),
            resolveMetricsSource("gke_Bad Project_us-central1_example-cluster", isDemo = false, settings = emptyMap()),
        )
    }

    @Test
    fun `a manual target on any context is used as typed, trimmed`() {
        val settings = mapOf("example-context" to gmp(project = " example-project ", location = "us-central1\n", cluster = " example-cluster"))

        assertEquals(SourceChoice.Gmp(triple, auto = false), resolveMetricsSource("example-context", isDemo = false, settings = settings))
    }

    @Test
    fun `a manual target wins over what the context name says`() {
        val settings = mapOf(gkeContext to gmp(project = "other-project"))

        assertEquals(
            SourceChoice.Gmp(GmpTarget("other-project", "us-central1", "example-cluster"), auto = false),
            resolveMetricsSource(gkeContext, isDemo = false, settings = settings),
        )
    }

    @Test
    fun `a manual target with an invalid field is incomplete`() {
        for (setting in listOf(gmp(project = "Bad Project"), gmp(location = ""), gmp(cluster = "a\"b"), gmp(project = "example-project\" or 1"))) {
            assertEquals(
                SourceChoice.None(NoSourceReason.IncompleteSetting),
                resolveMetricsSource("example-context", isDemo = false, settings = mapOf("example-context" to setting)),
                "$setting",
            )
        }
    }

    @Test
    fun `off disables a gke context`() {
        val settings = mapOf(gkeContext to MetricsSourceSetting(MetricsSourceMode.OFF))

        assertEquals(SourceChoice.None(NoSourceReason.Disabled), resolveMetricsSource(gkeContext, isDemo = false, settings = settings))
    }

    @Test
    fun `a demo context is the demo source`() {
        assertEquals(SourceChoice.Demo, resolveMetricsSource("demo-cluster (mock) #2", isDemo = false, settings = emptyMap()))
        assertEquals(SourceChoice.Demo, resolveMetricsSource(DemoContext.MOCK_CONTEXT_NAME, isDemo = false, settings = emptyMap()))
    }

    @Test
    fun `a connection that is the demo is the demo source whatever the context is called`() {
        assertEquals(SourceChoice.Demo, resolveMetricsSource("example-context", isDemo = true, settings = emptyMap()))
    }

    @Test
    fun `a manual target does not turn the demo into Managed Prometheus`() {
        val settings = mapOf(DemoContext.MOCK_CONTEXT_NAME to gmp())

        assertEquals(SourceChoice.Demo, resolveMetricsSource("demo-cluster (mock) #2", isDemo = true, settings = settings))
    }

    @Test
    fun `off stored under the demo row applies to every minted demo label`() {
        val settings = mapOf(DemoContext.MOCK_CONTEXT_NAME to MetricsSourceSetting(MetricsSourceMode.OFF))

        assertEquals(SourceChoice.None(NoSourceReason.Disabled), resolveMetricsSource("demo-cluster (mock) #2", isDemo = true, settings = settings))
        assertEquals(SourceChoice.None(NoSourceReason.Disabled), resolveMetricsSource(DemoContext.MOCK_CONTEXT_NAME, isDemo = true, settings = settings))
    }

    @Test
    fun `a setting stored for another cluster does not apply`() {
        val settings = mapOf("example-context" to MetricsSourceSetting(MetricsSourceMode.OFF))

        assertEquals(SourceChoice.Gmp(triple, auto = true), resolveMetricsSource(gkeContext, isDemo = false, settings = settings))
    }
}
