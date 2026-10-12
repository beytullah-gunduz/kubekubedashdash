package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.ShellEnvironment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `cpu sums the rate per container over a five minute window at the finest step`() {
        assertEquals(
            """sum by (container) (rate(container_cpu_usage_seconds_total{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0",container!="",container!="POD"}[300s]))""",
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
            """sum(max by (interface) (rate(container_network_receive_bytes_total{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0"}[300s])))""",
            PromQl.gmpPodQuery(PodSeriesKind.NetworkRx, target, pod, stepSec = 30),
        )
        assertEquals(
            """sum(max by (interface) (rate(container_network_transmit_bytes_total{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0"}[300s])))""",
            PromQl.gmpPodQuery(PodSeriesKind.NetworkTx, target, pod, stepSec = 30),
        )
    }

    @Test
    fun `throttling divides throttled periods by periods per container`() {
        val selector = """{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0",container!="",container!="POD"}"""
        assertEquals(
            "sum by (container) (rate(container_cpu_cfs_throttled_periods_total$selector[300s])) / " +
                "sum by (container) (rate(container_cpu_cfs_periods_total$selector[300s]))",
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
    fun `the rate window is never shorter than five minutes`() {
        assertEquals(300L, PromQl.rateWindowSec(30))
        assertEquals(300L, PromQl.rateWindowSec(120))
        assertEquals(300L, PromQl.rateWindowSec(180))
        assertEquals(300L, PromQl.rateWindowSec(300))
        assertEquals(720L, PromQl.rateWindowSec(720))
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

    // ── pod sets ────────────────────────────────────────────────────────────────

    private val oldReplicaSet = "redis-cache-5f7a3b1d0"
    private val newReplicaSet = "redis-cache-6d9f8c7b5"
    private val dottedReplicaSet = "redis-cache.v2-5f7a3b1d0"

    // 60 characters: "redis-cache-" plus 48.
    private val longReplicaSet = "redis-cache-" + "0123456789abcdef".repeat(3)

    // Unsorted on purpose: the pattern lists the names in order.
    private val podSet = PodSetRef("team-a", listOf(newReplicaSet, oldReplicaSet))
    private val podPattern = """pod=~"($oldReplicaSet-|$newReplicaSet-)[a-z0-9]{5}""""

    @Test
    fun `a pod prefix is the ReplicaSet name and a hyphen, cut to 58 characters`() {
        assertEquals("$newReplicaSet-", PromQl.podPrefix(newReplicaSet))

        val fiftySeven = "redis-cache-" + "x".repeat(45)
        assertEquals("$fiftySeven-", PromQl.podPrefix(fiftySeven))
        assertEquals(58, PromQl.podPrefix(fiftySeven).length)

        val fiftyEight = "redis-cache-" + "x".repeat(46)
        assertEquals(fiftyEight, PromQl.podPrefix(fiftyEight))

        assertEquals(60, longReplicaSet.length)
        assertEquals(longReplicaSet.take(58), PromQl.podPrefix(longReplicaSet))
        assertFalse(PromQl.podPrefix(longReplicaSet).endsWith("-"))
    }

    @Test
    fun `the pod pattern lists the sorted prefixes and ends with five characters, no hyphen after the group`() {
        assertEquals("($oldReplicaSet-|$newReplicaSet-)[a-z0-9]{5}", PromQl.podSetRegex(listOf(newReplicaSet, oldReplicaSet)))
        assertEquals("($oldReplicaSet-)[a-z0-9]{5}", PromQl.podSetRegex(listOf(oldReplicaSet, oldReplicaSet)))
    }

    @Test
    fun `a dot in a ReplicaSet name is escaped in the pattern and again in the string literal`() {
        val regex = PromQl.podSetRegex(listOf(dottedReplicaSet))
        assertEquals("""(redis-cache\.v2-5f7a3b1d0-)[a-z0-9]{5}""", regex)

        assertEquals(
            """namespace="team-a",pod=~"(redis-cache\\.v2-5f7a3b1d0-)[a-z0-9]{5}"""",
            PromQl.inClusterPodSetSelector("team-a", regex),
        )

        val matcher = Regex(regex)
        assertTrue(matcher.matches("redis-cache.v2-5f7a3b1d0-x7k2p"))
        assertFalse(matcher.matches("redis-cacheXv2-5f7a3b1d0-x7k2p"), "the dot matched any character")
    }

    @Test
    fun `a ReplicaSet name of 60 characters selects pods named from its first 58`() {
        val regex = PromQl.podSetRegex(listOf(longReplicaSet))
        assertEquals("(${longReplicaSet.take(58)})[a-z0-9]{5}", regex)

        val matcher = Regex(regex)
        assertTrue(matcher.matches(longReplicaSet.take(58) + "x7k2p"))
        assertFalse(matcher.matches(longReplicaSet + "-x7k2p"), "a pod name never carries the whole 60 characters")
    }

    @Test
    fun `the pod pattern matches a ReplicaSet's pods and nothing longer or shorter`() {
        val matcher = Regex(PromQl.podSetRegex(listOf(oldReplicaSet, newReplicaSet)))

        assertTrue(matcher.matches("$oldReplicaSet-x7k2p"))
        assertTrue(matcher.matches("$newReplicaSet-92f6w"))
        assertFalse(matcher.matches("$oldReplicaSet-x7k2p-extra"))
        assertFalse(matcher.matches("$oldReplicaSet-x7k2"))
        assertFalse(matcher.matches("$oldReplicaSet-x7k2pz"))
        assertFalse(matcher.matches("redis-cache-7c8d9e0f1-x7k2p"))
    }

    @Test
    fun `every regex metacharacter is escaped, and a hyphen is not`() {
        for (ch in "\\.+*?()|[]{}^$") {
            assertEquals("\\$ch", PromQl.regexEscape(ch.toString()), "$ch")
        }
        assertEquals("a-b_c", PromQl.regexEscape("a-b_c"))
    }

    @Test
    fun `an empty list or an invalid ReplicaSet name has no pattern, and the error names nothing`() {
        assertFailsWith<IllegalArgumentException> { PromQl.podSetRegex(emptyList()) }
        for (bad in listOf("Redis-Cache", "redis\"cache", "", "-redis", "redis-", "redis cache", "a|b", "redis_cache", "x".repeat(254))) {
            val error = assertFailsWith<IllegalArgumentException>(bad) { PromQl.podSetRegex(listOf(oldReplicaSet, bad)) }
            assertFalse(error.message.orEmpty().contains(oldReplicaSet) || (bad.isNotEmpty() && error.message.orEmpty().contains(bad)), "the message names a ReplicaSet: ${error.message}")
        }
    }

    @Test
    fun `a pod set is valid with a namespace label and DNS ReplicaSet names`() {
        assertTrue(PromQl.isValidPodSet(podSet))
        assertTrue(PromQl.isValidPodSet(PodSetRef("team-a", listOf(dottedReplicaSet, longReplicaSet))))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", emptyList())))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", listOf("Web"))))
        assertFalse(PromQl.isValidPodSet(PodSetRef("Team_A", listOf(oldReplicaSet))))
        assertFalse(PromQl.isValidPodSet(PodSetRef("", listOf(oldReplicaSet))))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a\"b", listOf(oldReplicaSet))))
    }

    @Test
    fun `the pod set selectors carry the pattern as a regex matcher`() {
        val regex = PromQl.podSetRegex(podSet.replicaSets)
        assertEquals(
            """cluster="example-cluster",location="us-central1",namespace="team-a",$podPattern""",
            PromQl.gmpPodSetSelector(target, "team-a", regex),
        )
        assertEquals("""namespace="team-a",$podPattern""", PromQl.inClusterPodSetSelector("team-a", regex))
    }

    private val gmpSelector = """cluster="example-cluster",location="us-central1",namespace="team-a",$podPattern"""
    private val inClusterSelector = """namespace="team-a",$podPattern"""

    private fun containers(selector: String) = """{$selector,container!="",container!="POD"}"""

    @Test
    fun `gmp per pod queries group by pod, with memory and network deduplicated`() {
        val c = containers(gmpSelector)
        fun query(kind: PodSeriesKind, stepSec: Long = 30) = PromQl.gmpPodSetQuery(kind, target, podSet, PodSetMode.PerPod, stepSec)

        assertEquals("sum by (pod) (rate(container_cpu_usage_seconds_total$c[300s]))", query(PodSeriesKind.Cpu))
        assertEquals("sum by (pod) (max by (pod, container) (container_memory_working_set_bytes$c))", query(PodSeriesKind.Memory))
        assertEquals("sum by (pod) (max by (pod, interface) (rate(container_network_receive_bytes_total{$gmpSelector}[300s])))", query(PodSeriesKind.NetworkRx))
        assertEquals("sum by (pod) (max by (pod, interface) (rate(container_network_transmit_bytes_total{$gmpSelector}[300s])))", query(PodSeriesKind.NetworkTx))
        assertEquals(
            "sum by (pod) (rate(container_cpu_cfs_throttled_periods_total$c[300s])) / sum by (pod) (rate(container_cpu_cfs_periods_total$c[300s]))",
            query(PodSeriesKind.Throttling),
        )
        assertEquals("sum by (pod) (rate(container_cpu_usage_seconds_total$c[720s]))", query(PodSeriesKind.Cpu, stepSec = 720))
    }

    @Test
    fun `gmp total queries sum the whole set`() {
        val c = containers(gmpSelector)
        fun query(kind: PodSeriesKind, stepSec: Long = 30) = PromQl.gmpPodSetQuery(kind, target, podSet, PodSetMode.Total, stepSec)

        assertEquals("sum(rate(container_cpu_usage_seconds_total$c[300s]))", query(PodSeriesKind.Cpu))
        assertEquals("sum(max by (pod, container) (container_memory_working_set_bytes$c))", query(PodSeriesKind.Memory))
        assertEquals("sum(max by (pod, interface) (rate(container_network_receive_bytes_total{$gmpSelector}[300s])))", query(PodSeriesKind.NetworkRx))
        assertEquals("sum(max by (pod, interface) (rate(container_network_transmit_bytes_total{$gmpSelector}[300s])))", query(PodSeriesKind.NetworkTx))
        assertEquals(
            "sum(rate(container_cpu_cfs_throttled_periods_total$c[300s])) / sum(rate(container_cpu_cfs_periods_total$c[300s]))",
            query(PodSeriesKind.Throttling),
        )
        assertEquals("sum(rate(container_cpu_usage_seconds_total$c[720s]))", query(PodSeriesKind.Cpu, stepSec = 720))
    }

    @Test
    fun `in-cluster per pod queries group by pod, with memory and network deduplicated`() {
        val c = containers(inClusterSelector)
        fun query(kind: PodSeriesKind, stepSec: Long = 30) = PromQl.inClusterPodSetQuery(kind, podSet, PodSetMode.PerPod, stepSec)

        assertEquals("sum by (pod) (rate(container_cpu_usage_seconds_total$c[240s]))", query(PodSeriesKind.Cpu))
        assertEquals("sum by (pod) (max by (pod, container) (container_memory_working_set_bytes$c))", query(PodSeriesKind.Memory))
        assertEquals("sum by (pod) (max by (pod, interface) (rate(container_network_receive_bytes_total{$inClusterSelector}[240s])))", query(PodSeriesKind.NetworkRx))
        assertEquals("sum by (pod) (max by (pod, interface) (rate(container_network_transmit_bytes_total{$inClusterSelector}[240s])))", query(PodSeriesKind.NetworkTx))
        assertEquals(
            "sum by (pod) (rate(container_cpu_cfs_throttled_periods_total$c[240s])) / sum by (pod) (rate(container_cpu_cfs_periods_total$c[240s]))",
            query(PodSeriesKind.Throttling),
        )
        assertEquals("sum by (pod) (rate(container_cpu_usage_seconds_total$c[720s]))", query(PodSeriesKind.Cpu, stepSec = 720))
    }

    @Test
    fun `in-cluster total queries sum the whole set`() {
        val c = containers(inClusterSelector)
        fun query(kind: PodSeriesKind, stepSec: Long = 30) = PromQl.inClusterPodSetQuery(kind, podSet, PodSetMode.Total, stepSec)

        assertEquals("sum(rate(container_cpu_usage_seconds_total$c[240s]))", query(PodSeriesKind.Cpu))
        assertEquals("sum(max by (pod, container) (container_memory_working_set_bytes$c))", query(PodSeriesKind.Memory))
        assertEquals("sum(max by (pod, interface) (rate(container_network_receive_bytes_total{$inClusterSelector}[240s])))", query(PodSeriesKind.NetworkRx))
        assertEquals("sum(max by (pod, interface) (rate(container_network_transmit_bytes_total{$inClusterSelector}[240s])))", query(PodSeriesKind.NetworkTx))
        assertEquals(
            "sum(rate(container_cpu_cfs_throttled_periods_total$c[240s])) / sum(rate(container_cpu_cfs_periods_total$c[240s]))",
            query(PodSeriesKind.Throttling),
        )
        assertEquals("sum(rate(container_cpu_usage_seconds_total$c[720s]))", query(PodSeriesKind.Cpu, stepSec = 720))
    }

    @Test
    fun `pod set queries never match on the image label and the in-cluster ones carry no cluster matcher`() {
        for (kind in PodSeriesKind.entries) {
            for (mode in PodSetMode.entries) {
                val gmp = PromQl.gmpPodSetQuery(kind, target, podSet, mode, stepSec = 30)
                val inCluster = PromQl.inClusterPodSetQuery(kind, podSet, mode, stepSec = 30)
                assertFalse(gmp.contains("image") || inCluster.contains("image"), "$kind $mode")
                assertFalse(inCluster.contains("cluster=") || inCluster.contains("location="), "$kind $mode: $inCluster")
            }
        }
    }

    @Test
    fun `a pod set query for an invalid set is refused`() {
        assertFailsWith<IllegalArgumentException> {
            PromQl.gmpPodSetQuery(PodSeriesKind.Cpu, target, PodSetRef("team-a", emptyList()), PodSetMode.Total, 30)
        }
        assertFailsWith<IllegalArgumentException> {
            PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, PodSetRef("team-a", listOf("Web")), PodSetMode.PerPod, 30)
        }
    }

    // ── scopes ──────────────────────────────────────────────────────────────────

    private val gmpScope = """cluster="example-cluster",location="us-central1","""
    private val containerFilters = """container!="",container!="POD""""

    @Test
    fun `gmp scope queries leave out the namespace matcher for every namespace`() {
        val scope = ScopeRef(emptySet())
        assertEquals(
            "sum(rate(container_cpu_usage_seconds_total{$gmpScope$containerFilters}[300s]))",
            PromQl.gmpScopeQuery(ScopeKind.CpuCores, target, scope, stepSec = 30),
        )
        assertEquals(
            "sum(max by (namespace, pod, container) (container_memory_working_set_bytes{$gmpScope$containerFilters}))",
            PromQl.gmpScopeQuery(ScopeKind.MemoryBytes, target, scope, stepSec = 30),
        )
    }

    @Test
    fun `gmp scope queries match one namespace exactly and several by regex, sorted`() {
        assertEquals(
            "sum(rate(container_cpu_usage_seconds_total{${gmpScope}namespace=\"team-a\",$containerFilters}[300s]))",
            PromQl.gmpScopeQuery(ScopeKind.CpuCores, target, ScopeRef(setOf("team-a")), stepSec = 30),
        )
        assertEquals(
            "sum(max by (namespace, pod, container) (container_memory_working_set_bytes{${gmpScope}namespace=~\"team-a|team-b\",$containerFilters}))",
            PromQl.gmpScopeQuery(ScopeKind.MemoryBytes, target, ScopeRef(linkedSetOf("team-b", "team-a")), stepSec = 30),
        )
        assertEquals(
            "sum(rate(container_cpu_usage_seconds_total{${gmpScope}namespace=~\"team-a|team-b\",$containerFilters}[720s]))",
            PromQl.gmpScopeQuery(ScopeKind.CpuCores, target, ScopeRef(setOf("team-a", "team-b")), stepSec = 720),
        )
    }

    @Test
    fun `in-cluster scope queries start at the namespace matcher, or at the container filters for every namespace`() {
        assertEquals(
            "sum(rate(container_cpu_usage_seconds_total{$containerFilters}[240s]))",
            PromQl.inClusterScopeQuery(ScopeKind.CpuCores, ScopeRef(emptySet()), stepSec = 30),
        )
        assertEquals(
            "sum(max by (namespace, pod, container) (container_memory_working_set_bytes{$containerFilters}))",
            PromQl.inClusterScopeQuery(ScopeKind.MemoryBytes, ScopeRef(emptySet()), stepSec = 30),
        )
        assertEquals(
            "sum(rate(container_cpu_usage_seconds_total{namespace=\"team-a\",$containerFilters}[240s]))",
            PromQl.inClusterScopeQuery(ScopeKind.CpuCores, ScopeRef(setOf("team-a")), stepSec = 30),
        )
        assertEquals(
            "sum(max by (namespace, pod, container) (container_memory_working_set_bytes{namespace=~\"team-a|team-b\",$containerFilters}))",
            PromQl.inClusterScopeQuery(ScopeKind.MemoryBytes, ScopeRef(linkedSetOf("team-b", "team-a")), stepSec = 30),
        )
        assertEquals(
            "sum(rate(container_cpu_usage_seconds_total{namespace=\"team-a\",$containerFilters}[720s]))",
            PromQl.inClusterScopeQuery(ScopeKind.CpuCores, ScopeRef(setOf("team-a")), stepSec = 720),
        )
    }

    @Test
    fun `a scope is valid when every namespace is a DNS label`() {
        assertTrue(PromQl.isValidScope(ScopeRef(emptySet())))
        assertTrue(PromQl.isValidScope(ScopeRef(setOf("team-a", "team-b"))))
        assertFalse(PromQl.isValidScope(ScopeRef(setOf("team-a", "Team_B"))))
        assertFalse(PromQl.isValidScope(ScopeRef(setOf("team-a\"b"))))
        assertFalse(PromQl.isValidScope(ScopeRef(setOf("team-a|team-b"))))
    }
}
