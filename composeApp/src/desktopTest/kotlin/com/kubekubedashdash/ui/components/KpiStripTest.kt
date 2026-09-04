package com.kubekubedashdash.ui.components

import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.errorPodStatuses
import com.kubekubedashdash.ui.screens.cluster.viewmodel.pendingPodStatuses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-function tests for the KPI strip's model: [podKpis], [nodeKpis],
 * [podKpiStatuses], [nodeKpiStatuses], [activeKpiId] and [usagePercentLabel].
 * [KpiStrip] itself is a thin `@Composable` renderer over this model and has
 * no test coverage here — this repo has no Compose UI test infrastructure.
 */
class KpiStripTest {

    private fun container(name: String) = ContainerInfo(
        name = name,
        image = "fake.example/app:latest",
        ready = true,
        restartCount = 0,
        state = "running",
    )

    private fun pod(name: String, status: String) = PodInfo(
        uid = "uid-$name",
        name = name,
        namespace = "example-ns",
        status = status,
        ready = "1/1",
        restarts = 0,
        age = "5m",
        node = "node-1",
        ip = "10.0.0.1",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = listOf(container("main")),
    )

    private fun node(name: String, status: String) = NodeInfo(
        uid = "uid-$name",
        name = name,
        status = status,
        roles = "worker",
        version = "v1.30.0",
        os = "linux",
        arch = "arm64",
        containerRuntime = "containerd",
        cpu = "4",
        memory = "8Gi",
        pods = "110",
        age = "1d",
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    private val mixedPods = listOf(
        pod("pod-1", "Running"),
        pod("pod-2", "Pending"),
        pod("pod-3", "ContainerCreating"),
        pod("pod-4", "CrashLoopBackOff"),
        pod("pod-5", "OOMKilled"),
        pod("pod-6", "Terminated"),
    )

    // ── podKpis: counts ──────────────────────────────────────────────────────

    @Test
    fun `podKpis counts failing and pending from the canonical status vocabulary`() {
        val kpis = podKpis(mixedPods, usage = null)

        assertEquals("6 pods", kpis.first { it.id == "total" }.label)
        assertEquals("3 failing", kpis.first { it.id == "failing" }.label)
        assertEquals("2 pending", kpis.first { it.id == "pending" }.label)
    }

    @Test
    fun `podKpis omits failing and pending when their count is zero`() {
        val allHealthy = listOf(pod("pod-1", "Running"), pod("pod-2", "Running"))

        val kpis = podKpis(allHealthy, usage = null)

        assertTrue(kpis.none { it.id == "failing" })
        assertTrue(kpis.none { it.id == "pending" })
    }

    @Test
    fun `podKpis keeps the active chip present and at zero when its count drops to zero`() {
        val allHealthy = listOf(pod("pod-1", "Running"))

        val kpis = podKpis(allHealthy, usage = null, activeId = "failing")

        val failing = kpis.first { it.id == "failing" }
        assertEquals("0 failing", failing.label)
        assertEquals(KpiTone.Error, failing.tone)
    }

    @Test
    fun `podKpis renders total first`() {
        val kpis = podKpis(mixedPods, usage = null, activeId = "failing")

        assertEquals("total", kpis.first().id)
    }

    // ── podKpiStatuses / nodeKpiStatuses: the anti-fork test ────────────────

    @Test
    fun `podKpiStatuses mirrors the canonical vocabulary, not a private copy`() {
        assertEquals(errorPodStatuses(), podKpiStatuses("failing"))
        assertEquals(pendingPodStatuses(), podKpiStatuses("pending"))
    }

    @Test
    fun `podKpiStatuses is empty for non-filtering ids`() {
        assertEquals(emptySet(), podKpiStatuses("total"))
        assertEquals(emptySet(), podKpiStatuses("cpu"))
    }

    @Test
    fun `nodeKpiStatuses is NotReady for notReady and empty otherwise`() {
        assertEquals(setOf("NotReady"), nodeKpiStatuses("notReady"))
        assertEquals(emptySet(), nodeKpiStatuses("total"))
        assertEquals(emptySet(), nodeKpiStatuses("cpu"))
    }

    // ── activeKpiId ──────────────────────────────────────────────────────────

    @Test
    fun `activeKpiId finds the chip whose vocabulary equals the filter`() {
        val ids = listOf("total", "failing", "pending", "cpu", "mem")

        assertEquals("failing", activeKpiId(errorPodStatuses(), ::podKpiStatuses, ids))
    }

    @Test
    fun `activeKpiId is null when the filter is null or matches no chip`() {
        val ids = listOf("total", "failing", "pending", "cpu", "mem")

        assertNull(activeKpiId(null, ::podKpiStatuses, ids))
        assertNull(activeKpiId(setOf("Running"), ::podKpiStatuses, ids))
    }

    @Test
    fun `activeKpiId never returns total`() {
        val ids = listOf("total", "failing", "pending", "cpu", "mem")

        // "total" has no vocabulary of its own (podKpiStatuses("total") is
        // empty), so it can never match a non-null filter — but assert the
        // contract directly in case that ever changes.
        assertTrue(activeKpiId(errorPodStatuses(), ::podKpiStatuses, ids) != "total")
        assertTrue(activeKpiId(pendingPodStatuses(), ::podKpiStatuses, ids) != "total")
    }

    // ── nodeKpis ─────────────────────────────────────────────────────────────

    @Test
    fun `nodeKpis counts NotReady nodes`() {
        val nodes = listOf(node("node-1", "Ready"), node("node-2", "NotReady"), node("node-3", "NotReady"))

        val kpis = nodeKpis(nodes, usage = null, podsUsed = 0, podsCapacity = 0)

        assertEquals("3 nodes", kpis.first { it.id == "total" }.label)
        assertEquals("2 NotReady", kpis.first { it.id == "notReady" }.label)
    }

    @Test
    fun `nodeKpis hides the pods chip when capacity is zero`() {
        val nodes = listOf(node("node-1", "Ready"))

        val withoutCapacity = nodeKpis(nodes, usage = null, podsUsed = 0, podsCapacity = 0)
        val withCapacity = nodeKpis(nodes, usage = null, podsUsed = 12, podsCapacity = 110)

        assertTrue(withoutCapacity.none { it.id == "pods" })
        assertEquals("12 / 110 pods", withCapacity.first { it.id == "pods" }.label)
    }

    // ── usagePercentLabel ────────────────────────────────────────────────────

    @Test
    fun `usagePercentLabel formats the worked values from the plan`() {
        assertEquals("CPU 11 %", usagePercentLabel("CPU", used = 11, capacity = 100, available = true))
        assertEquals("CPU 5.4 %", usagePercentLabel("CPU", used = 54, capacity = 1000, available = true))
        assertEquals("CPU 10.0 %", usagePercentLabel("CPU", used = 996, capacity = 10000, available = true))
        assertEquals("CPU 10 %", usagePercentLabel("CPU", used = 10, capacity = 100, available = true))
    }

    @Test
    fun `usagePercentLabel is em dash when capacity is zero or unavailable`() {
        assertEquals("CPU —", usagePercentLabel("CPU", used = 0, capacity = 0, available = true))
        assertEquals("CPU —", usagePercentLabel("CPU", used = 5, capacity = 100, available = false))
    }

    @Test
    fun `podKpis tags cpu and mem Muted when usage is null`() {
        val kpis = podKpis(mixedPods, usage = null)

        val cpu = kpis.first { it.id == "cpu" }
        val mem = kpis.first { it.id == "mem" }
        assertEquals("CPU —", cpu.label)
        assertEquals(KpiTone.Muted, cpu.tone)
        assertEquals("Mem —", mem.label)
        assertEquals(KpiTone.Muted, mem.tone)
    }

    @Test
    fun `podKpis tags cpu and mem Muted when metrics are unavailable`() {
        val usage = ResourceUsageSummary(
            cpuUsedMillis = 500,
            cpuCapacityMillis = 4000,
            memoryUsedBytes = 500,
            memoryCapacityBytes = 4000,
            metricsAvailable = false,
        )

        val kpis = podKpis(mixedPods, usage)

        assertEquals(KpiTone.Muted, kpis.first { it.id == "cpu" }.tone)
        assertEquals(KpiTone.Muted, kpis.first { it.id == "mem" }.tone)
    }

    @Test
    fun `podKpis renders cpu and mem percentages when metrics are available`() {
        val usage = ResourceUsageSummary(
            cpuUsedMillis = 11,
            cpuCapacityMillis = 100,
            memoryUsedBytes = 10,
            memoryCapacityBytes = 100,
            metricsAvailable = true,
        )

        val kpis = podKpis(mixedPods, usage)

        val cpu = kpis.first { it.id == "cpu" }
        val mem = kpis.first { it.id == "mem" }
        assertEquals("CPU 11 %", cpu.label)
        assertEquals(KpiTone.Neutral, cpu.tone)
        assertEquals("Mem 10 %", mem.label)
        assertEquals(KpiTone.Neutral, mem.tone)
    }
}
