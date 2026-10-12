package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.models.ContainerResources

/** The pod a history query is about; [containers] feeds the demo cluster's generated series and the reference lines. */
data class PodRef(val namespace: String, val name: String, val containers: List<ContainerResources> = emptyList())

/** Pods selected by name pattern in one namespace: the pods of the given ReplicaSets, current and old. */
data class PodSetRef(val namespace: String, val replicaSets: List<String>)

enum class PodSeriesKind { Cpu, Memory, NetworkRx, NetworkTx, Throttling }

/** [PerPod] gives one series per pod; [Total] sums the whole set into one. */
enum class PodSetMode { PerPod, Total }

/** A namespace scope for cluster-level sums; empty = every namespace. */
data class ScopeRef(val namespaces: Set<String>)

enum class ScopeKind { CpuCores, MemoryBytes }

/** One sample: unix seconds and the value (cores, bytes, bytes per second, or a 0..1 fraction). */
data class SeriesPoint(val epochSec: Long, val value: Double)

/** [name] is the container for per-container kinds, the pod for a per-pod set, and "" for pod-level ones (network) and sums. */
data class Series(val name: String, val points: List<SeriesPoint>)

/** A query window; [stepSec] is also the gap threshold the chart uses. */
data class QueryWindow(val startEpochSec: Long, val endEpochSec: Long, val stepSec: Long)

enum class HistoryRange(val label: String, val seconds: Long) {
    M15("15m", 900),
    H1("1h", 3_600),
    H6("6h", 21_600),
    H24("24h", 86_400),
    ;

    /** About [TARGET_POINTS] points, never finer than [MIN_STEP_SEC]; the end is aligned down to the step. */
    fun window(nowEpochSec: Long): QueryWindow {
        val step = maxOf(MIN_STEP_SEC, ceilToMultiple(ceilDiv(seconds, TARGET_POINTS), MIN_STEP_SEC))
        val end = nowEpochSec - nowEpochSec % step
        return QueryWindow(end - seconds, end, step)
    }

    companion object {
        const val TARGET_POINTS = 120L
        const val MIN_STEP_SEC = 30L
    }
}

private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

private fun ceilToMultiple(value: Long, multiple: Long): Long = ceilDiv(value, multiple) * multiple

enum class HistoryFailure {
    GcloudUnavailable,
    NotSignedIn,
    Forbidden,
    NoContainerMetrics,
    BadQuery,
    Unavailable,

    /** The role cannot GET services/proxy in the Prometheus namespace. */
    NoProxyAccess,

    /** The API server does not know the Service. */
    ServiceGone,

    /** The Service exists but the Prometheus API is not at the configured path. */
    WrongPath,
}

sealed interface SeriesResult {
    data class Ok(val series: List<Series>) : SeriesResult

    /** [detail] is safe to show and log: an HTTP status, a Prometheus errorType or "timeout"; never a token, query or name. */
    data class Failed(val failure: HistoryFailure, val detail: String = "") : SeriesResult
}

interface MetricsHistorySource {
    /** Shown beside the charts: where the data comes from. */
    val label: String

    suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult

    /** [Series.name] is the pod for [PodSetMode.PerPod] and "" for [PodSetMode.Total]. */
    suspend fun podSetSeries(kind: PodSeriesKind, pods: PodSetRef, mode: PodSetMode, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")

    /** One series named "": the sum over every pod of [scope]. */
    suspend fun scopeSeries(kind: ScopeKind, scope: ScopeRef, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")
}
