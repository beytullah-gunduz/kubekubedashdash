package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.util.DEMO_MEMORY_PRESSURE_TARGETS
import kotlin.math.PI
import kotlin.math.sin

private const val DEMO_FALLBACK_MEMORY_BYTES = 256L * 1024 * 1024

/**
 * Pod history for the demo cluster's fake Prometheus (DemoPrometheusDispatcher): smooth waves
 * derived from the pod's own requests and limits. Same input, same output: the charts hold still
 * between refreshes, and tests can pin them.
 */
internal fun demoSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): List<Series> {
    val times = window.startEpochSec..window.endEpochSec step window.stepSec
    val containers = pod.containers.ifEmpty { listOf(ContainerResources("app")) }
    val podKey = "${pod.namespace}/${pod.name}"
    val podSeed = podKey.hashCode()

    fun perContainer(of: List<ContainerResources>, value: (ContainerResources, Int, Long) -> Double): List<Series> = of.map { c ->
        val seed = "$podKey/${c.name}".hashCode()
        Series(c.name, times.map { t -> SeriesPoint(t, value(c, seed, t)) })
    }

    val series = when (kind) {
        PodSeriesKind.Cpu -> perContainer(containers) { c, seed, t ->
            (c.cpuRequestMillis ?: 100L) / 1000.0 * (0.3 + 0.9 * wave(seed, t, 1800.0))
        }

        PodSeriesKind.Memory -> perContainer(containers) { c, seed, t ->
            val cap = c.memoryLimitBytes ?: c.memoryRequestBytes?.times(2) ?: DEMO_FALLBACK_MEMORY_BYTES
            val level = DEMO_MEMORY_PRESSURE_TARGETS[podKey]?.let { it + 0.02 * (wave(seed, t, 600.0) - 0.5) }
                ?: (0.35 + 0.3 * wave(seed, t, 5400.0))
            cap * level
        }

        PodSeriesKind.NetworkRx -> listOf(Series("", times.map { t -> SeriesPoint(t, 65_536.0 * (0.3 + wave(podSeed, t, 900.0))) }))

        PodSeriesKind.NetworkTx -> listOf(Series("", times.map { t -> SeriesPoint(t, 24_576.0 * (0.3 + wave(podSeed + 1, t, 900.0))) }))

        PodSeriesKind.Throttling -> perContainer(containers.filter { it.cpuLimitMillis != null }) { _, seed, t ->
            maxOf(0.0, wave(seed, t, 1200.0) - 0.65) * 1.5
        }
    }
    return series.sortedBy { it.name }
}

// 0.05..0.95: a slow wave plus a faster harmonic, phase-shifted per seed so no two lines move together.
private fun wave(seed: Int, t: Long, periodSec: Double): Double {
    val phase = (seed and 0xFFFF) / 65_535.0 * 2 * PI
    return 0.5 + 0.3 * sin(2 * PI * t / periodSec + phase) + 0.15 * sin(2 * PI * t / (periodSec / 4.3) + 2 * phase)
}
