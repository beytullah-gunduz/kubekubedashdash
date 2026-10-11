package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.util.ShellEnvironment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The demo cluster's generated series: deterministic, shaped by the pod's own limits, and holding the pinned pods near their limit. */
class DemoSeriesTest {

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

    private val window = HistoryRange.H1.window(1_000_000)
    private val gib = 1L shl 30

    private val app = ContainerResources("app", cpuRequestMillis = 1_000, cpuLimitMillis = 2_000, memoryRequestBytes = gib / 2, memoryLimitBytes = gib)
    private val sidecar = ContainerResources("sidecar", cpuRequestMillis = 100)
    private val pod = PodRef("team-a", "web-0", listOf(app, sidecar))

    @Test
    fun `the same input gives the same output`() {
        for (kind in PodSeriesKind.entries) {
            assertEquals(demoSeries(kind, pod, window), demoSeries(kind, pod, window), "$kind")
        }
    }

    @Test
    fun `there is one point per step from the start to the end`() {
        for (series in demoSeries(PodSeriesKind.Cpu, pod, window)) {
            assertEquals((window.endEpochSec - window.startEpochSec) / window.stepSec + 1, series.points.size.toLong())
            assertEquals(window.startEpochSec, series.points.first().epochSec)
            assertEquals(window.endEpochSec, series.points.last().epochSec)
            assertEquals(setOf(window.stepSec), series.points.zipWithNext { a, b -> b.epochSec - a.epochSec }.toSet())
        }
    }

    @Test
    fun `per container kinds give one series per container sorted by name`() {
        val unsorted = PodRef("team-a", "web-0", listOf(ContainerResources("zeta"), ContainerResources("alpha")))

        assertEquals(listOf("alpha", "zeta"), demoSeries(PodSeriesKind.Cpu, unsorted, window).map { it.name })
        assertEquals(listOf("alpha", "zeta"), demoSeries(PodSeriesKind.Memory, unsorted, window).map { it.name })
    }

    @Test
    fun `a pod with no known containers gets one called app`() {
        assertEquals(listOf("app"), demoSeries(PodSeriesKind.Cpu, PodRef("team-a", "web-0"), window).map { it.name })
    }

    @Test
    fun `cpu follows the request`() {
        val byName = demoSeries(PodSeriesKind.Cpu, pod, window).associateBy { it.name }

        // 1 core requested: between 0.3 and 1.2 cores. 100m requested: a tenth of that.
        assertTrue(byName.getValue("app").points.all { it.value > 0.3 && it.value < 1.2 }, "app cpu out of range")
        assertTrue(byName.getValue("sidecar").points.all { it.value > 0.03 && it.value < 0.12 }, "sidecar cpu out of range")
    }

    @Test
    fun `memory stays under the limit and is not pinned for an ordinary pod`() {
        val points = demoSeries(PodSeriesKind.Memory, pod, window).single { it.name == "app" }.points

        assertTrue(points.all { it.value > 0.3 * gib && it.value < 0.7 * gib }, "memory out of the 30..70 percent band")
    }

    @Test
    fun `the pinned pod holds near its limit`() {
        val pinned = PodRef("default", "backend-api-6c4f8d9b2-xyz99", listOf(ContainerResources("app", memoryLimitBytes = gib)))
        val wide = HistoryRange.H24.window(1_000_000)

        val points = demoSeries(PodSeriesKind.Memory, pinned, wide).single().points

        assertTrue(points.all { it.value >= 0.93 * gib && it.value <= 0.97 * gib }, "pinned memory left [0.93, 0.97] of the limit: ${points.minOf { it.value } / gib}..${points.maxOf { it.value } / gib}")
    }

    @Test
    fun `memory without a limit falls back to twice the request, then to a fixed size`() {
        val requestOnly = PodRef("team-a", "web-0", listOf(ContainerResources("app", memoryRequestBytes = gib)))
        val nothing = PodRef("team-a", "web-0", listOf(ContainerResources("app")))

        assertTrue(demoSeries(PodSeriesKind.Memory, requestOnly, window).single().points.all { it.value > 0.3 * 2 * gib && it.value < 0.7 * 2 * gib })
        assertTrue(demoSeries(PodSeriesKind.Memory, nothing, window).single().points.all { it.value > 0.3 * 256 * 1024 * 1024 && it.value < 0.7 * 256 * 1024 * 1024 })
    }

    @Test
    fun `throttling is only drawn for containers with a cpu limit`() {
        val series = demoSeries(PodSeriesKind.Throttling, pod, window)

        assertEquals(listOf("app"), series.map { it.name })
        assertTrue(series.single().points.all { it.value >= 0.0 && it.value <= 1.0 }, "throttling is a 0..1 fraction")
        assertEquals(emptyList(), demoSeries(PodSeriesKind.Throttling, PodRef("team-a", "web-0", listOf(sidecar)), window))
    }

    @Test
    fun `network is one pod level series with no name, and received differs from sent`() {
        val rx = demoSeries(PodSeriesKind.NetworkRx, pod, window)
        val tx = demoSeries(PodSeriesKind.NetworkTx, pod, window)

        assertEquals(listOf(""), rx.map { it.name })
        assertEquals(listOf(""), tx.map { it.name })
        assertTrue(rx.single().points.any { it.value > 0.0 })
        assertTrue(rx.single().points.map { it.value } != tx.single().points.map { it.value }, "received and sent must not be the same curve")
    }

    @Test
    fun `different pods get different curves`() {
        val other = PodRef("team-a", "web-1", listOf(app, sidecar))

        assertTrue(demoSeries(PodSeriesKind.Cpu, pod, window) != demoSeries(PodSeriesKind.Cpu, other, window))
    }
}
