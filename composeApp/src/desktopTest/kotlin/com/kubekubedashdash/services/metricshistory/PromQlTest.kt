package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.ShellEnvironment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            """max by (container) (avg_over_time(container_memory_working_set_bytes{cluster="example-cluster",location="us-central1",namespace="team-a",pod="web-0",container!="",container!="POD"}[300s]))""",
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
            """max by (container) (avg_over_time(container_memory_working_set_bytes{namespace="team-a",pod="web-0",container!="",container!="POD"}[240s]))""",
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
    private val podSet = PromQl.ofReplicaSets("team-a", listOf(newReplicaSet, oldReplicaSet))!!
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
    fun `a pod set is valid with a namespace label and a lower-case pattern`() {
        assertTrue(PromQl.isValidPodSet(podSet))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", "")))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", "(Web-)[a-z0-9]{5}")))
        assertFalse(PromQl.isValidPodSet(PodSetRef("Team_A", podSet.pattern)))
        assertFalse(PromQl.isValidPodSet(PodSetRef("", podSet.pattern)))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a\"b", podSet.pattern)))
    }

    @Test
    fun `the pod set selectors carry the pattern as a regex matcher`() {
        val regex = podSet.pattern
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
        assertEquals("sum by (pod) (max by (pod, container) (avg_over_time(container_memory_working_set_bytes$c[300s])))", query(PodSeriesKind.Memory))
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
        assertEquals("sum(max by (pod, container) (avg_over_time(container_memory_working_set_bytes$c[300s])))", query(PodSeriesKind.Memory))
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
        assertEquals("sum by (pod) (max by (pod, container) (avg_over_time(container_memory_working_set_bytes$c[240s])))", query(PodSeriesKind.Memory))
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
        assertEquals("sum(max by (pod, container) (avg_over_time(container_memory_working_set_bytes$c[240s])))", query(PodSeriesKind.Memory))
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
        assertNull(PromQl.ofReplicaSets("team-a", emptyList()))
        assertNull(PromQl.ofReplicaSets("team-a", listOf("Web")))
    }

    // ── pod set factories ───────────────────────────────────────────────────────

    @Test
    fun `ofReplicaSets writes what podSetRegex writes, so a Deployment's queries do not change`() {
        for (names in listOf(
            listOf(newReplicaSet, oldReplicaSet),
            listOf(oldReplicaSet, oldReplicaSet),
            listOf(dottedReplicaSet),
            listOf(longReplicaSet),
            listOf(dottedReplicaSet, longReplicaSet, oldReplicaSet),
        )) {
            assertEquals(PodSetRef("team-a", PromQl.podSetRegex(names)), PromQl.ofReplicaSets("team-a", names), "$names")
        }
        assertEquals("($oldReplicaSet-|$newReplicaSet-)[a-z0-9]{5}", PromQl.ofReplicaSets("team-a", listOf(newReplicaSet, oldReplicaSet))?.pattern)
        assertEquals("""(redis-cache\.v2-5f7a3b1d0-)[a-z0-9]{5}""", PromQl.ofReplicaSets("team-a", listOf(dottedReplicaSet))?.pattern)
    }

    @Test
    fun `every factory refuses a namespace that is not a label, a name that is not a DNS name and an empty list`() {
        for (namespace in listOf("", "Team-A", "team_a", "team-a\"b", "team a")) {
            assertNull(PromQl.ofReplicaSets(namespace, listOf(oldReplicaSet)), namespace)
            assertNull(PromQl.ofJob(namespace, "nightly-backup", indexed = false), namespace)
            assertNull(PromQl.ofCronJob(namespace, "nightly-backup", listOf("nightly-backup-29123456")), namespace)
            assertNull(PromQl.ofExactPods(namespace, listOf("web-0")), namespace)
            assertNull(PromQl.ofSparkPods(namespace, listOf("spark-pi-success-driver"), emptyList()), namespace)
        }
        for (bad in listOf("Nightly-Backup", "nightly_backup", "nightly|backup", "nightly\"backup", "", "-nightly", "nightly-")) {
            assertNull(PromQl.ofReplicaSets("team-a", listOf(oldReplicaSet, bad)), bad)
            assertNull(PromQl.ofJob("team-a", bad, indexed = false), bad)
            assertNull(PromQl.ofJob("team-a", bad, indexed = true), bad)
            assertNull(PromQl.ofCronJob("team-a", bad, listOf("nightly-backup-29123456")), bad)
            assertNull(PromQl.ofExactPods("team-a", listOf("web-0", bad)), bad)
            assertNull(PromQl.ofSparkPods("team-a", listOf(bad), listOf("spark-pi-success-exec-1")), bad)
            assertNull(PromQl.ofSparkPods("team-a", listOf("spark-pi-success-driver"), listOf(bad)), bad)
        }
        assertNull(PromQl.ofExactPods("team-a", emptyList()))
        assertNull(PromQl.ofSparkPods("team-a", emptyList(), emptyList()))
    }

    @Test
    fun `a Job's pods are its name, a hyphen and five characters, cut to 58 like any generated name`() {
        assertEquals("(nightly-backup-29123456-)[a-z0-9]{5}", PromQl.ofJob("team-a", "nightly-backup-29123456", indexed = false)?.pattern)
        assertEquals("""(log-rotation\.v2-)[a-z0-9]{5}""", PromQl.ofJob("team-a", "log-rotation.v2", indexed = false)?.pattern)

        val long = "nightly-backup-" + "abcdefghij".repeat(5)
        assertEquals(65, long.length)
        assertEquals("(${long.take(58)})[a-z0-9]{5}", PromQl.ofJob("team-a", long, indexed = false)?.pattern)

        val matcher = Regex(PromQl.ofJob("team-a", "nightly-backup-29123456", indexed = false)!!.pattern)
        assertTrue(matcher.matches("nightly-backup-29123456-x7k2p"))
        assertFalse(matcher.matches("nightly-backup-29123456-3-x7k2p"), "an index belongs to an Indexed Job")
        assertFalse(matcher.matches("nightly-backup-29123456-x7k2p-extra"))
        assertFalse(matcher.matches("nightly-backup-2912345-x7k2p"))
    }

    @Test
    fun `an Indexed Job with a short name matches any index`() {
        assertEquals("(db-vacuum-[0-9]+-)[a-z0-9]{5}", PromQl.ofJob("team-a", "db-vacuum", indexed = true)?.pattern)

        val matcher = Regex(PromQl.ofJob("team-a", "db-vacuum", indexed = true)!!.pattern)
        assertTrue(matcher.matches("db-vacuum-0-x7k2p"))
        assertTrue(matcher.matches("db-vacuum-123456-x7k2p"))
        assertFalse(matcher.matches("db-vacuum-x7k2p"), "a pod of a Job that is not Indexed has no index")
        assertFalse(matcher.matches("db-vacuum-12-x7k2p-1"))
        assertFalse(matcher.matches("db-vacuum-a-x7k2p"))
    }

    // The name Kubernetes gives an Indexed Job's pod (podGenerateNameWithIndex, then SimpleNameGenerator's cut to 58).
    private fun indexedPodName(job: String, index: Int, random: String = "x7k2p"): String {
        val appendIndex = "-$index-"
        val prefix = (job + appendIndex).let { if (it.length > 58) job.take(58 - appendIndex.length) + appendIndex else it }
        return prefix.take(58) + random
    }

    @Test
    fun `an Indexed Job name of 50 characters is never cut, one of 51 or more is cut at every index width`() {
        val fifty = "nightly-backup-" + "abcdefghij".repeat(4).take(35)
        assertEquals(50, fifty.length)
        assertEquals("($fifty-[0-9]+-)[a-z0-9]{5}", PromQl.ofJob("team-a", fifty, indexed = true)?.pattern)
        assertEquals("$fifty-123456-x7k2p", indexedPodName(fifty, 123456))

        val fiftyOne = fifty + "k"
        val cut = PromQl.ofJob("team-a", fiftyOne, indexed = true)!!.pattern
        assertEquals(
            "(" +
                "$fiftyOne-[0-9]{1}-|" +
                "$fiftyOne-[0-9]{2}-|" +
                "$fiftyOne-[0-9]{3}-|" +
                "$fiftyOne-[0-9]{4}-|" +
                "$fiftyOne-[0-9]{5}-|" +
                "$fifty-[0-9]{6}-" +
                ")[a-z0-9]{5}",
            cut,
        )
    }

    @Test
    fun `an Indexed Job name of 55 characters is matched at index 0, 7, 42 and 123456, and its neighbours are not`() {
        val job = "nightly-backup-" + "abcdefghij".repeat(4)
        assertEquals(55, job.length)
        val pattern = PromQl.ofJob("team-a", job, indexed = true)!!.pattern
        assertEquals(
            "(" +
                "$job-[0-9]{1}-|" +
                "${job.take(54)}-[0-9]{2}-|" +
                "${job.take(53)}-[0-9]{3}-|" +
                "${job.take(52)}-[0-9]{4}-|" +
                "${job.take(51)}-[0-9]{5}-|" +
                "${job.take(50)}-[0-9]{6}-" +
                ")[a-z0-9]{5}",
            pattern,
        )
        val matcher = Regex(pattern)

        for (index in listOf(0, 7, 42, 123456)) {
            val name = indexedPodName(job, index)
            assertTrue(matcher.matches(name), "$name should match")
            assertEquals(63, name.length, name)

            // One more character before the index, one fewer, a different first character and a missing index.
            val digits = index.toString()
            val base = name.removeSuffix("-$digits-x7k2p")
            assertFalse(matcher.matches("${base}x-$digits-x7k2p"), "one more character before the index")
            assertFalse(matcher.matches("${base.dropLast(1)}-$digits-x7k2p"), "one fewer character before the index")
            assertFalse(matcher.matches("z" + name.drop(1)), "another name")
            assertFalse(matcher.matches("$base-x7k2p"), "no index")
            assertFalse(matcher.matches(name + "z"), "a sixth random character")
        }
        assertFalse(matcher.matches(indexedPodName(job, 1234567)), "a seven-digit index is not matched")
        assertFalse(matcher.matches(job + "-x7k2p"), "a pod of the same Job without an index")
    }

    @Test
    fun `an Indexed Job pattern matches the name Kubernetes generates for every name length and index width`() {
        for (length in 1..80) {
            val job = ("a" + "bcdefghijk".repeat(8)).take(length)
            val pattern = PromQl.ofJob("team-a", job, indexed = true)!!.pattern
            val matcher = Regex(pattern)
            for (index in listOf(0, 9, 10, 99, 100, 999, 1000, 9999, 10000, 99999, 100000, 999999)) {
                assertTrue(matcher.matches(indexedPodName(job, index)), "$job at index $index")
            }
        }
    }

    @Test
    fun `a CronJob's pods are its name, eight digits, an optional index and five characters`() {
        val pattern = PromQl.ofCronJob("team-a", "nightly-backup", emptyList())!!.pattern
        assertEquals("(nightly-backup-[0-9]{8}-([0-9]+-)?)[a-z0-9]{5}", pattern)

        val matcher = Regex(pattern)
        assertTrue(matcher.matches("nightly-backup-29123456-x7k2p"))
        assertTrue(matcher.matches("nightly-backup-29123456-3-x7k2p"), "an Indexed job template")
        assertFalse(matcher.matches("nightly-backup-2912345-x7k2p"))
        assertFalse(matcher.matches("nightly-backup-291234567-x7k2p"))
        assertFalse(matcher.matches("nightly-backup-extra-29123456-x7k2p"), "another CronJob that starts with this name")
        assertFalse(matcher.matches("nightly-backup-29123456-x7k2p-1"))
        assertFalse(matcher.matches("nightly-backup-29123456-3-4-x7k2p"))
        assertEquals("""(log-rotation\.v2-[0-9]{8}-([0-9]+-)?)[a-z0-9]{5}""", PromQl.ofCronJob("team-a", "log-rotation.v2", emptyList())?.pattern)
    }

    @Test
    fun `a CronJob name of 48 characters keeps its Jobs' names whole in their pods, one of 49 is read from its listed Jobs`() {
        val fortyEight = "nightly-backup-" + "abcdefghij".repeat(4).take(33)
        assertEquals(48, fortyEight.length)
        assertEquals("($fortyEight-[0-9]{8}-([0-9]+-)?)[a-z0-9]{5}", PromQl.ofCronJob("team-a", fortyEight, emptyList())?.pattern)
        val jobPod = "$fortyEight-29123456-x7k2p"
        assertEquals(63, jobPod.length)
        assertTrue(Regex(PromQl.ofCronJob("team-a", fortyEight, emptyList())!!.pattern).matches(jobPod))

        val fortyNine = fortyEight + "k"
        val jobs = listOf("$fortyNine-29123457", "$fortyNine-29123456")
        assertEquals(PromQl.ofReplicaSets("team-a", jobs), PromQl.ofCronJob("team-a", fortyNine, jobs))
        // 49 + 9 = 58 characters: the Job's whole name fills the generated base and its pods carry no hyphen after it.
        assertEquals("(${jobs[1]}|${jobs[0]})[a-z0-9]{5}", PromQl.ofCronJob("team-a", fortyNine, jobs)?.pattern)
        assertNull(PromQl.ofCronJob("team-a", fortyNine, emptyList()), "no Job is listed, so no prefix is known")
    }

    @Test
    fun `a CronJob selects a Job started by hand by its own prefix and the scheduled ones as before`() {
        val scheduled = listOf("nightly-backup-29123456", "nightly-backup-29123457")
        assertEquals(
            PromQl.ofCronJob("team-a", "nightly-backup", emptyList()),
            PromQl.ofCronJob("team-a", "nightly-backup", scheduled),
            "scheduled Jobs add nothing to the pattern",
        )

        val ref = PromQl.ofCronJob("team-a", "nightly-backup", scheduled + listOf("log-rotation", "nightly-backup-extra", "nightly-backup-2912345", "nightly-backup-291234567", "log-rotation"))!!
        assertEquals(
            "(nightly-backup-[0-9]{8}-([0-9]+-)?|log-rotation-|nightly-backup-2912345-|nightly-backup-291234567-|nightly-backup-extra-)[a-z0-9]{5}",
            ref.pattern,
        )
        assertTrue(PromQl.isValidPodSet(ref))

        val matcher = Regex(ref.pattern)
        assertTrue(matcher.matches("nightly-backup-29123456-x7k2p"))
        assertTrue(matcher.matches("nightly-backup-29123457-3-x7k2p"), "an Indexed job template")
        assertTrue(matcher.matches("log-rotation-x7k2p"))
        assertTrue(matcher.matches("nightly-backup-extra-x7k2p"))
        assertFalse(matcher.matches("log-rotation-extra-x7k2p"))
        assertFalse(matcher.matches("log-rotation-x7k2"))
        assertFalse(matcher.matches("db-vacuum-x7k2p"), "a Job that is not listed")
    }

    @Test
    fun `a dotted Job started by hand is escaped and a name too long to keep whole is cut like its pods`() {
        assertEquals(
            """(nightly-backup-[0-9]{8}-([0-9]+-)?|log-rotation\.v2-)[a-z0-9]{5}""",
            PromQl.ofCronJob("team-a", "nightly-backup", listOf("log-rotation.v2"))?.pattern,
        )

        val long = "log-rotation-" + "abcdefghij".repeat(5)
        assertEquals(63, long.length)
        val ref = PromQl.ofCronJob("team-a", "nightly-backup", listOf(long))!!
        assertEquals("(nightly-backup-[0-9]{8}-([0-9]+-)?|${long.take(58)})[a-z0-9]{5}", ref.pattern)
        assertTrue(Regex(ref.pattern).matches(long.take(58) + "x7k2p"))
    }

    @Test
    fun `a CronJob refuses a listed Job whose name is not a DNS subdomain`() {
        for (bad in listOf("Log-Rotation", "log_rotation", "log|rotation", "log\"rotation", "", "-log-rotation", "log-rotation-")) {
            assertNull(PromQl.ofCronJob("team-a", "nightly-backup", listOf("nightly-backup-29123456", bad)), bad)
        }
    }

    @Test
    fun `a CronJob of 52 characters keeps one alternative per distinct cut prefix and stays a valid pod set`() {
        val cronJob = "nightly-backup-" + "abcdefghij".repeat(4).take(37)
        assertEquals(52, cronJob.length)
        // A Job name of 52 + 1 + 8 = 61 characters is cut to its first 58 in the pod name, so the last three digits are lost.
        val jobs = (0 until 70).map { "$cronJob-${29_123_000 + it * 60}" }
        val prefixes = jobs.map { PromQl.podPrefix(it) }.distinct()
        assertTrue(prefixes.size < jobs.size, "several Jobs share a pod-name prefix")
        assertTrue(PromQl.ofReplicaSets("team-a", jobs)!!.pattern.length > 4_000, "the Job names alone would be too long")

        val ref = PromQl.ofCronJob("team-a", cronJob, jobs)!!

        assertTrue(PromQl.isValidPodSet(ref), "${ref.pattern.length} characters")
        assertEquals(prefixes.size, ref.pattern.removePrefix("(").removeSuffix(")[a-z0-9]{5}").split('|').size)
        assertEquals(ref, PromQl.ofCronJob("team-a", cronJob, jobs.reversed() + jobs))
        val matcher = Regex(ref.pattern)
        for (job in jobs) {
            assertTrue(matcher.matches(PromQl.podPrefix(job) + "x7k2p"), job)
        }
        assertFalse(matcher.matches(PromQl.podPrefix("$cronJob-29130000") + "x7k2p"), "a Job that is not listed")
    }

    @Test
    fun `exact pods are listed sorted, once, with their dots escaped`() {
        assertEquals("(frontend-1|web-0)", PromQl.ofExactPods("team-a", listOf("web-0", "frontend-1", "web-0"))?.pattern)
        assertEquals("""(redis-cache\.v2-0)""", PromQl.ofExactPods("team-a", listOf("redis-cache.v2-0"))?.pattern)

        val matcher = Regex(PromQl.ofExactPods("team-a", listOf("web-0", "frontend-1"))!!.pattern)
        assertTrue(matcher.matches("web-0"))
        assertFalse(matcher.matches("web-01"))
        assertFalse(matcher.matches("xweb-0"))
    }

    @Test
    fun `a namespace selects every pod of it and is valid, and a name that is not a label selects none`() {
        val ref = PromQl.ofNamespace("team-a")
        assertEquals(PodSetRef("team-a", ".+"), ref)
        assertTrue(PromQl.isValidPodSet(ref!!))
        for (namespace in listOf("", "Team-A", "team_a", "team-a\"b", "team a")) {
            assertNull(PromQl.ofNamespace(namespace), namespace)
        }
    }

    @Test
    fun `a namespace's queries over the 6h window carry the pod regex that matches every pod`() {
        val ref = PromQl.ofNamespace("team-a")!!
        // 6h is charted at a 180 s step: the Managed Prometheus window grows to 300 s, the in-cluster one to 240 s.
        val stepSec = 180L

        assertEquals(
            """sum(rate(container_cpu_usage_seconds_total{cluster="example-cluster",location="us-central1",namespace="team-a",pod=~".+",container!="",container!="POD"}[300s]))""",
            PromQl.gmpPodSetQuery(PodSeriesKind.Cpu, target, ref, PodSetMode.Total, stepSec),
        )
        assertEquals(
            """sum by (pod) (rate(container_cpu_usage_seconds_total{cluster="example-cluster",location="us-central1",namespace="team-a",pod=~".+",container!="",container!="POD"}[300s]))""",
            PromQl.gmpPodSetQuery(PodSeriesKind.Cpu, target, ref, PodSetMode.PerPod, stepSec),
        )
        assertEquals(
            """sum(rate(container_cpu_usage_seconds_total{namespace="team-a",pod=~".+",container!="",container!="POD"}[240s]))""",
            PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, ref, PodSetMode.Total, stepSec),
        )
    }

    private val sparkDriver = "spark-etl-nightly-driver"
    private val sparkStem = "spark-etl-nightly-1a2b3c4d5e6f7890"

    private fun sparkExecutors(count: Int, stem: String = sparkStem) = (1..count).map { "$stem-exec-$it" }

    @Test
    fun `a Spark app with up to 60 pods is selected by exact name`() {
        val executors = sparkExecutors(59)
        val ref = PromQl.ofSparkPods("team-a", listOf(sparkDriver), executors)!!
        assertEquals(PromQl.ofExactPods("team-a", listOf(sparkDriver) + executors), ref)
        assertTrue(ref.pattern.contains("$sparkStem-exec-59"))
        assertFalse(ref.pattern.contains("[0-9]+"))

        assertEquals("(spark-pi-success-driver)", PromQl.ofSparkPods("team-a", listOf("spark-pi-success-driver"), emptyList())?.pattern)
    }

    @Test
    fun `a Spark app with more than 60 pods selects its executors by stem`() {
        val executors = sparkExecutors(60)
        val pattern = PromQl.ofSparkPods("team-a", listOf(sparkDriver), executors)!!.pattern
        assertEquals("($sparkDriver|$sparkStem-exec-[0-9]+)", pattern)

        val matcher = Regex(pattern)
        assertTrue(matcher.matches(sparkDriver))
        assertTrue(matcher.matches("$sparkStem-exec-1"))
        assertTrue(matcher.matches("$sparkStem-exec-1000"), "an executor the status does not list yet")
        assertFalse(matcher.matches("$sparkStem-exec-"))
        assertFalse(matcher.matches("$sparkStem-exec-x"))
        assertFalse(matcher.matches("$sparkStem-exec-1-extra"))
        assertFalse(matcher.matches("spark-etl-hourly-1a2b3c4d5e6f7890-exec-1"))
    }

    @Test
    fun `executors are grouped by the stem before the last -exec-, and one named otherwise keeps its exact name`() {
        val otherStem = "spark-etl-nightly-2b3c4d5e6f708901"
        val odd = "spark-etl-nightly-sidecar"
        val tricky = "x-exec-1-exec-2"
        val dotted = "spark.etl-exec-3"
        val executors = sparkExecutors(40) + sparkExecutors(40, otherStem) + listOf(odd, tricky, dotted)
        val pattern = PromQl.ofSparkPods("team-a", listOf(sparkDriver, "spark-etl-hourly-driver", sparkDriver), executors)?.pattern

        // Drivers first, sorted; then the executor alternatives, sorted as strings.
        assertEquals(
            "(spark-etl-hourly-driver|$sparkDriver|" +
                "$sparkStem-exec-[0-9]+|$otherStem-exec-[0-9]+|$odd|" +
                """spark\.etl-exec-[0-9]+|x-exec-1-exec-[0-9]+)""",
            pattern,
        )
        assertNull(PromQl.ofSparkPods("team-a", listOf(sparkDriver), sparkExecutors(70) + "$sparkStem-exec-"), "a trailing hyphen is no DNS name")
    }

    @Test
    fun `Spark executors are selected by exact name up to 60 and by stem past that, whatever the drivers`() {
        val sixty = sparkExecutors(60)
        assertEquals(PromQl.ofExactPods("team-a", sixty), PromQl.ofSparkExecutors("team-a", sixty))
        assertFalse(PromQl.ofSparkExecutors("team-a", sixty)!!.pattern.contains("[0-9]+"))

        // With a driver, 60 executors already pass ofSparkPods' threshold; here the executors' own count decides.
        assertEquals("($sparkStem-exec-[0-9]+)", PromQl.ofSparkExecutors("team-a", sparkExecutors(61))?.pattern)
        assertEquals("(spark-pi-success-exec-1)", PromQl.ofSparkExecutors("team-a", listOf("spark-pi-success-exec-1"))?.pattern)
    }

    @Test
    fun `Spark executors past the threshold keep the stem grouping of the Spark pod set, and a name without -exec- its exact form`() {
        val otherStem = "spark-etl-nightly-2b3c4d5e6f708901"
        val odd = "spark-etl-nightly-sidecar"
        val dotted = "spark.etl-exec-3"
        val executors = sparkExecutors(40) + sparkExecutors(40, otherStem) + listOf(odd, dotted)

        val pattern = PromQl.ofSparkExecutors("team-a", executors)!!.pattern

        assertEquals("($sparkStem-exec-[0-9]+|$otherStem-exec-[0-9]+|$odd|" + """spark\.etl-exec-[0-9]+)""", pattern)
        // The executors' alternatives are the Spark pod set's with the drivers taken out.
        assertEquals(PromQl.ofSparkPods("team-a", emptyList(), executors)?.pattern, pattern)
        assertTrue(Regex(pattern).matches("$sparkStem-exec-1000"), "an executor the status does not list yet")
        assertFalse(Regex(pattern).matches(sparkDriver))
    }

    @Test
    fun `Spark executors are refused when none is named, the namespace is not a label or a name is not a DNS name`() {
        assertNull(PromQl.ofSparkExecutors("team-a", emptyList()))
        for (namespace in listOf("", "Team-A", "team_a", "team-a\"b", "team a")) {
            assertNull(PromQl.ofSparkExecutors(namespace, listOf("spark-pi-success-exec-1")), namespace)
            assertNull(PromQl.ofSparkExecutors(namespace, sparkExecutors(70)), namespace)
        }
        for (bad in listOf("Spark-Exec-1", "spark_exec_1", "spark|exec", "spark\"exec", "", "-spark", "spark-")) {
            assertNull(PromQl.ofSparkExecutors("team-a", listOf("spark-pi-success-exec-1", bad)), bad)
            assertNull(PromQl.ofSparkExecutors("team-a", sparkExecutors(70) + bad), bad)
        }
    }

    // ── pod set validity ────────────────────────────────────────────────────────

    @Test
    fun `every factory's pattern is a valid pod set`() {
        val refs = listOf(
            PromQl.ofReplicaSets("team-a", listOf(dottedReplicaSet, longReplicaSet, oldReplicaSet)),
            PromQl.ofJob("team-a", "nightly-backup-29123456", indexed = false),
            PromQl.ofJob("team-a", "log-rotation.v2", indexed = true),
            PromQl.ofJob("team-a", "nightly-backup-" + "abcdefghij".repeat(4), indexed = true),
            PromQl.ofCronJob("team-a", "nightly-backup", emptyList()),
            PromQl.ofExactPods("team-a", listOf("web-0", "redis-cache.v2-0")),
            PromQl.ofSparkPods("team-a", listOf(sparkDriver), sparkExecutors(80)),
            PromQl.ofSparkExecutors("team-a", sparkExecutors(80)),
        )
        for (ref in refs) {
            assertTrue(PromQl.isValidPodSet(ref!!), ref.pattern)
        }
    }

    @Test
    fun `a pattern with a quote, an upper-case letter or any other character is refused`() {
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", "web\"x")))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", "(Web-)[a-z0-9]{5}")))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", "")))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", "   ")))
        for (ch in listOf('^', '$', ',', ' ', '=', '~', '_', '/', '\n', '\'', 'A', 'é')) {
            assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", "(web-${ch}x)")), "'$ch' is not a pattern character")
        }
        for (ch in "abcxyz0189.-\\()|[]{}+?*") {
            assertTrue(PromQl.isValidPodSet(PodSetRef("team-a", "web${ch}x")), "'$ch' is a pattern character")
        }
    }

    @Test
    fun `a pattern of 4000 characters is valid and one of 4001 is not`() {
        assertTrue(PromQl.isValidPodSet(PodSetRef("team-a", "a".repeat(4_000))))
        assertFalse(PromQl.isValidPodSet(PodSetRef("team-a", "a".repeat(4_001))))
    }

    @Test
    fun `the query carries a Job's pattern once through the string-literal escaping`() {
        val ref = PromQl.ofJob("team-a", "log-rotation.v2", indexed = false)!!

        val selector = """namespace="team-a",pod=~"(log-rotation\\.v2-)[a-z0-9]{5}""""
        assertEquals(selector, PromQl.inClusterPodSetSelector(ref.namespace, ref.pattern))
        assertEquals(
            """sum(rate(container_cpu_usage_seconds_total{$selector,container!="",container!="POD"}[240s]))""",
            PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, ref, PodSetMode.Total, 30),
        )
        assertEquals(
            """sum(rate(container_cpu_usage_seconds_total{cluster="example-cluster",location="us-central1",$selector,container!="",container!="POD"}[300s]))""",
            PromQl.gmpPodSetQuery(PodSeriesKind.Cpu, target, ref, PodSetMode.Total, 30),
        )
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
            "sum(max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{$gmpScope$containerFilters}[300s])))",
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
            "sum(max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{${gmpScope}namespace=~\"team-a|team-b\",$containerFilters}[300s])))",
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
            "sum(max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{$containerFilters}[240s])))",
            PromQl.inClusterScopeQuery(ScopeKind.MemoryBytes, ScopeRef(emptySet()), stepSec = 30),
        )
        assertEquals(
            "sum(rate(container_cpu_usage_seconds_total{namespace=\"team-a\",$containerFilters}[240s]))",
            PromQl.inClusterScopeQuery(ScopeKind.CpuCores, ScopeRef(setOf("team-a")), stepSec = 30),
        )
        assertEquals(
            "sum(max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{namespace=~\"team-a|team-b\",$containerFilters}[240s])))",
            PromQl.inClusterScopeQuery(ScopeKind.MemoryBytes, ScopeRef(linkedSetOf("team-b", "team-a")), stepSec = 30),
        )
        assertEquals(
            "sum(rate(container_cpu_usage_seconds_total{namespace=\"team-a\",$containerFilters}[720s]))",
            PromQl.inClusterScopeQuery(ScopeKind.CpuCores, ScopeRef(setOf("team-a")), stepSec = 720),
        )
    }

    @Test
    fun `memory and CPU of one query average over the same window`() {
        val windowRegex = Regex("""\[(\d+)s\]""")
        fun windows(vararg queries: String) = queries.flatMap { q -> windowRegex.findAll(q).map { it.groupValues[1].toLong() } }.toSet()

        for (step in listOf(30L, 720L)) {
            val gmp = maxOf(step, 300L)
            val inCluster = maxOf(step, 240L)
            assertEquals(
                setOf(gmp),
                windows(
                    PromQl.gmpPodQuery(PodSeriesKind.Cpu, target, pod, step),
                    PromQl.gmpPodQuery(PodSeriesKind.Memory, target, pod, step),
                ),
            )
            assertEquals(
                setOf(inCluster),
                windows(
                    PromQl.inClusterPodQuery(PodSeriesKind.Cpu, pod, step),
                    PromQl.inClusterPodQuery(PodSeriesKind.Memory, pod, step),
                ),
            )
            assertEquals(
                setOf(gmp),
                windows(
                    PromQl.gmpPodSetQuery(PodSeriesKind.Cpu, target, podSet, PodSetMode.PerPod, step),
                    PromQl.gmpPodSetQuery(PodSeriesKind.Memory, target, podSet, PodSetMode.PerPod, step),
                ),
            )
            assertEquals(
                setOf(gmp),
                windows(
                    PromQl.gmpScopeQuery(ScopeKind.CpuCores, target, ScopeRef(emptySet()), step),
                    PromQl.gmpScopeQuery(ScopeKind.MemoryBytes, target, ScopeRef(emptySet()), step),
                ),
            )
        }
    }

    @Test
    fun `a scope is valid when every namespace is a DNS label`() {
        assertTrue(PromQl.isValidScope(ScopeRef(emptySet())))
        assertTrue(PromQl.isValidScope(ScopeRef(setOf("team-a", "team-b"))))
        assertFalse(PromQl.isValidScope(ScopeRef(setOf("team-a", "Team_B"))))
        assertFalse(PromQl.isValidScope(ScopeRef(setOf("team-a\"b"))))
        assertFalse(PromQl.isValidScope(ScopeRef(setOf("team-a|team-b"))))
    }

    // ── Spark ───────────────────────────────────────────────────────────────────

    private val sparkContainer = """container=~"spark-kubernetes-(driver|executor)""""

    @Test
    fun `gmp Spark queries group per pod or per role and select by container name`() {
        val every = ScopeRef(emptySet())
        val two = ScopeRef(linkedSetOf("team-b", "team-a"))
        assertEquals(
            "sum by (namespace, pod) (rate(container_cpu_usage_seconds_total{$gmpScope$sparkContainer}[300s]))",
            PromQl.gmpSparkQuery(ScopeKind.CpuCores, target, every, SparkGrouping.ByPod, stepSec = 30),
        )
        assertEquals(
            "sum by (container) (rate(container_cpu_usage_seconds_total{$gmpScope$sparkContainer}[300s]))",
            PromQl.gmpSparkQuery(ScopeKind.CpuCores, target, every, SparkGrouping.ByRole, stepSec = 30),
        )
        assertEquals(
            "sum by (namespace, pod) (max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{$gmpScope$sparkContainer}[300s])))",
            PromQl.gmpSparkQuery(ScopeKind.MemoryBytes, target, every, SparkGrouping.ByPod, stepSec = 30),
        )
        assertEquals(
            "sum by (container) (max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{$gmpScope$sparkContainer}[300s])))",
            PromQl.gmpSparkQuery(ScopeKind.MemoryBytes, target, every, SparkGrouping.ByRole, stepSec = 30),
        )
        assertEquals(
            "sum by (namespace, pod) (rate(container_cpu_usage_seconds_total{${gmpScope}namespace=~\"team-a|team-b\",$sparkContainer}[300s]))",
            PromQl.gmpSparkQuery(ScopeKind.CpuCores, target, two, SparkGrouping.ByPod, stepSec = 30),
        )
        assertEquals(
            "sum by (container) (rate(container_cpu_usage_seconds_total{${gmpScope}namespace=~\"team-a|team-b\",$sparkContainer}[300s]))",
            PromQl.gmpSparkQuery(ScopeKind.CpuCores, target, two, SparkGrouping.ByRole, stepSec = 30),
        )
        assertEquals(
            "sum by (namespace, pod) (max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{${gmpScope}namespace=~\"team-a|team-b\",$sparkContainer}[300s])))",
            PromQl.gmpSparkQuery(ScopeKind.MemoryBytes, target, two, SparkGrouping.ByPod, stepSec = 30),
        )
        assertEquals(
            "sum by (container) (max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{${gmpScope}namespace=~\"team-a|team-b\",$sparkContainer}[300s])))",
            PromQl.gmpSparkQuery(ScopeKind.MemoryBytes, target, two, SparkGrouping.ByRole, stepSec = 30),
        )
    }

    @Test
    fun `in-cluster Spark queries carry no cluster matcher and use the four minute window`() {
        assertEquals(
            "sum by (namespace, pod) (rate(container_cpu_usage_seconds_total{namespace=\"team-a\",$sparkContainer}[240s]))",
            PromQl.inClusterSparkQuery(ScopeKind.CpuCores, ScopeRef(setOf("team-a")), SparkGrouping.ByPod, stepSec = 30),
        )
        assertEquals(
            "sum by (container) (max by (namespace, pod, container) (avg_over_time(container_memory_working_set_bytes{$sparkContainer}[240s])))",
            PromQl.inClusterSparkQuery(ScopeKind.MemoryBytes, ScopeRef(emptySet()), SparkGrouping.ByRole, stepSec = 30),
        )
    }
}
