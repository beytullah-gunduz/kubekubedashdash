package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.ShellEnvironment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The exact PromQL the Managed Prometheus source sends; a changed matcher changes the numbers, so the strings are pinned. */
class PromQlTest {

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

    private val target = GmpTarget("example-project", "us-central1", "example-cluster")
    private val pod = PodRef("team-a", "web-0")

    @Test
    fun `cpu sums the rate per container over a two minute window at the finest step`() {
        assertEquals(
            """sum by (container) (rate(container_cpu_usage_seconds_total{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0",container!="",container!="POD"}[120s]))""",
            PromQl.gmpPodQuery(PodSeriesKind.Cpu, target, pod, stepSec = 30),
        )
    }

    @Test
    fun `the rate window grows with a coarse step`() {
        assertEquals(
            """sum by (container) (rate(container_cpu_usage_seconds_total{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0",container!="",container!="POD"}[720s]))""",
            PromQl.gmpPodQuery(PodSeriesKind.Cpu, target, pod, stepSec = 720),
        )
    }

    @Test
    fun `memory takes the max per container`() {
        assertEquals(
            """max by (container) (container_memory_working_set_bytes{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0",container!="",container!="POD"})""",
            PromQl.gmpPodQuery(PodSeriesKind.Memory, target, pod, stepSec = 30),
        )
    }

    @Test
    fun `network deduplicates per interface and keeps the pod level selector`() {
        assertEquals(
            """sum(max by (interface) (rate(container_network_receive_bytes_total{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0"}[120s])))""",
            PromQl.gmpPodQuery(PodSeriesKind.NetworkRx, target, pod, stepSec = 30),
        )
        assertEquals(
            """sum(max by (interface) (rate(container_network_transmit_bytes_total{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0"}[120s])))""",
            PromQl.gmpPodQuery(PodSeriesKind.NetworkTx, target, pod, stepSec = 30),
        )
    }

    @Test
    fun `throttling divides throttled periods by periods per container`() {
        val selector = """{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0",container!="",container!="POD"}"""
        assertEquals(
            "sum by (container) (rate(container_cpu_cfs_throttled_periods_total$selector[120s])) / " +
                "sum by (container) (rate(container_cpu_cfs_periods_total$selector[120s]))",
            PromQl.gmpPodQuery(PodSeriesKind.Throttling, target, pod, stepSec = 30),
        )
    }

    @Test
    fun `the probe counts the cluster's cpu series by cluster and location only`() {
        assertEquals(
            """count(container_cpu_usage_seconds_total{cluster="example-cluster",location="us-central1"})""",
            PromQl.gmpProbeQuery(target),
        )
    }

    @Test
    fun `no query matches on the image label`() {
        for (kind in PodSeriesKind.entries) {
            val query = PromQl.gmpPodQuery(kind, target, pod, stepSec = 30)
            assertFalse(query.contains("image"), "the image label does not deduplicate, but $kind uses it: $query")
        }
    }

    @Test
    fun `escape protects quotes and backslashes`() {
        assertEquals("a\\\"b\\\\c", PromQl.escape("a\"b\\c"))
    }

    @Test
    fun `a quote in a pod name cannot end the string literal`() {
        val selector = PromQl.gmpPodSelector(target, PodRef("team-a", "we\"b"))
        assertEquals(
            """cluster="example-cluster",location="us-central1",namespace="team-a",pod="we\"b"""",
            selector,
        )
    }

    @Test
    fun `the rate window is never shorter than two minutes`() {
        assertEquals(120L, PromQl.rateWindowSec(30))
        assertEquals(120L, PromQl.rateWindowSec(120))
        assertEquals(180L, PromQl.rateWindowSec(180))
    }

    // ── the in-cluster queries ──────────────────────────────────────────────────

    @Test
    fun `in-cluster cpu sums the rate per container over four minutes at the finest step`() {
        assertEquals(
            """sum by (container) (rate(container_cpu_usage_seconds_total{namespace="team-a",pod="web-0",container!="",container!="POD"}[240s]))""",
            PromQl.inClusterPodQuery(PodSeriesKind.Cpu, pod, stepSec = 30),
        )
    }

    @Test
    fun `in-cluster memory takes the max per container`() {
        assertEquals(
            """max by (container) (container_memory_working_set_bytes{namespace="team-a",pod="web-0",container!="",container!="POD"})""",
            PromQl.inClusterPodQuery(PodSeriesKind.Memory, pod, stepSec = 30),
        )
    }

    @Test
    fun `in-cluster network keeps the pod level selector and deduplicates per interface`() {
        assertEquals(
            """sum(max by (interface) (rate(container_network_receive_bytes_total{namespace="team-a",pod="web-0"}[240s])))""",
            PromQl.inClusterPodQuery(PodSeriesKind.NetworkRx, pod, stepSec = 30),
        )
        assertEquals(
            """sum(max by (interface) (rate(container_network_transmit_bytes_total{namespace="team-a",pod="web-0"}[240s])))""",
            PromQl.inClusterPodQuery(PodSeriesKind.NetworkTx, pod, stepSec = 30),
        )
    }

    @Test
    fun `in-cluster throttling divides throttled periods by periods per container`() {
        val selector = """{namespace="team-a",pod="web-0",container!="",container!="POD"}"""
        assertEquals(
            "sum by (container) (rate(container_cpu_cfs_throttled_periods_total$selector[240s])) / " +
                "sum by (container) (rate(container_cpu_cfs_periods_total$selector[240s]))",
            PromQl.inClusterPodQuery(PodSeriesKind.Throttling, pod, stepSec = 30),
        )
    }

    @Test
    fun `in-cluster queries carry no cluster or location matcher and no image label`() {
        for (kind in PodSeriesKind.entries) {
            val query = PromQl.inClusterPodQuery(kind, pod, stepSec = 30)
            assertFalse(query.contains("cluster=") || query.contains("location="), "$kind: $query")
            assertFalse(query.contains("image"), "$kind: $query")
        }
    }

    @Test
    fun `the in-cluster probe counts every cpu series`() {
        assertEquals("count(container_cpu_usage_seconds_total)", PromQl.inClusterProbeQuery())
    }

    @Test
    fun `the in-cluster rate window is never shorter than four minutes`() {
        assertEquals(240L, PromQl.inClusterRateWindowSec(30))
        assertEquals(240L, PromQl.inClusterRateWindowSec(240))
        assertEquals(720L, PromQl.inClusterRateWindowSec(720))
        assertEquals(
            """sum by (container) (rate(container_cpu_usage_seconds_total{namespace="team-a",pod="web-0",container!="",container!="POD"}[720s]))""",
            PromQl.inClusterPodQuery(PodSeriesKind.Cpu, pod, stepSec = 720),
        )
    }

    @Test
    fun `a quote in a pod name cannot end the in-cluster string literal`() {
        assertEquals("""namespace="team-a",pod="we\"b"""", PromQl.inClusterPodSelector(PodRef("team-a", "we\"b")))
    }
}
