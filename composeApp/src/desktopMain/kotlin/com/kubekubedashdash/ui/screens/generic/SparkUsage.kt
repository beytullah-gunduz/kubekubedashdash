package com.kubekubedashdash.ui.screens.generic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodUsage
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.models.podUsageKey
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.Kpi
import com.kubekubedashdash.ui.components.KpiTone
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.UsageLevel
import com.kubekubedashdash.ui.components.percentNumber
import com.kubekubedashdash.ui.screens.pods.UsageCellContent
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize

/** One SparkApplication's live footprint: usage from metrics-server, requests from its live pods' specs. */
internal data class SparkAppUsage(
    // Non-terminated pods attributed to the application.
    val livePods: Int,
    // Null when none of its pods has a usage sample.
    val cpuUsedMillis: Long?,
    val memoryUsedBytes: Long?,
    val cpuRequestMillis: Long,
    val memoryRequestBytes: Long,
)

/** Label the Spark operator puts on an application's driver and executor pods. */
internal const val SPARK_APP_NAME_LABEL = "sparkoperator.k8s.io/app-name"

internal const val SPARK_OPERATOR_API_GROUP = "sparkoperator.k8s.io"

/** Whether a generic screen lists the Spark operator's SparkApplications. */
internal fun isSparkApplicationKind(kind: String, apiGroup: String?): Boolean = apiGroup == SPARK_OPERATOR_API_GROUP && kind == "SparkApplication"

/** The key of an application in [sparkAppUsages]'s result: its uid, or namespace/name while the uid is blank. */
internal fun sparkUsageKey(app: GenericResourceInfo): String = app.uid.ifBlank { app.fallbackKey }

/**
 * Each application's footprint keyed by [sparkUsageKey], for every app in [apps] (an app with no live pod maps to
 * `livePods = 0`, null usage, 0 requests).
 *
 * A pod of the same namespace belongs to the application (1) named by its [SPARK_APP_NAME_LABEL] label, else (2) whose
 * uid it is owned by (the driver), else (3) whose attributed driver owns it (the executors). Only pods that are not
 * `Succeeded`/`Failed` count, as for the scheduler. Requests sum the pod's app containers and native sidecars
 * ([PodInfo.resources]); regular init containers are ignored (Spark pods have none by default) and a container without
 * a request adds 0. Usage sums the samples of [podUsages] (null when no live pod has one).
 */
internal fun sparkAppUsages(apps: List<GenericResourceInfo>, pods: List<PodInfo>, podUsages: Map<String, PodUsage>): Map<String, SparkAppUsage> {
    val byName = apps.associateBy { it.namespace.orEmpty() to it.name }
    val byUid = apps.filter { it.uid.isNotBlank() }.associateBy { it.uid }

    // Pod index -> its application.
    val attributed = HashMap<Int, GenericResourceInfo>()
    // Pod uid -> application, the anchors of the pass that follows the driver's ownership to its executors.
    val appByPodUid = HashMap<String, GenericResourceInfo>()
    pods.forEachIndexed { i, pod ->
        val app = pod.labels[SPARK_APP_NAME_LABEL]?.let { byName[pod.namespace to it] }
            ?: pod.owners.firstNotNullOfOrNull { byUid[it.uid] }
        if (app != null && app.namespace.orEmpty() == pod.namespace) {
            attributed[i] = app
            if (pod.uid.isNotBlank()) appByPodUid[pod.uid] = app
        }
    }
    pods.forEachIndexed { i, pod ->
        if (i in attributed) return@forEachIndexed
        val app = pod.owners.firstNotNullOfOrNull { appByPodUid[it.uid] }
        if (app != null && app.namespace.orEmpty() == pod.namespace) attributed[i] = app
    }

    val livePodsByApp = HashMap<String, MutableList<PodInfo>>()
    attributed.forEach { (i, app) ->
        val pod = pods[i]
        if (pod.phase != "Succeeded" && pod.phase != "Failed") livePodsByApp.getOrPut(sparkUsageKey(app)) { mutableListOf() }.add(pod)
    }
    return apps.associate { app ->
        val key = sparkUsageKey(app)
        val live = livePodsByApp[key].orEmpty()
        val samples = live.mapNotNull { podUsages[podUsageKey(it.namespace, it.name)] }
        key to SparkAppUsage(
            livePods = live.size,
            cpuUsedMillis = if (samples.isEmpty()) null else samples.sumOf { it.cpuMillis },
            memoryUsedBytes = if (samples.isEmpty()) null else samples.sumOf { it.memoryBytes },
            cpuRequestMillis = live.sumOf { pod -> pod.resources.sumOf { it.cpuRequestMillis ?: 0L } },
            memoryRequestBytes = live.sumOf { pod -> pod.resources.sumOf { it.memoryRequestBytes ?: 0L } },
        )
    }
}

/**
 * The Spark KPI strip: CPU used, CPU requested with its share of [summary]'s allocatable, then the same for memory,
 * over every application in [usages]. The share is requested over every node's allocatable, so it is omitted while the
 * allocatable is unknown: a namespace-limited account (no node list), a cluster without metrics-server (the usage
 * summary reads no node then), or before the first sample.
 */
internal fun sparkKpis(usages: Collection<SparkAppUsage>, summary: ResourceUsageSummary?): List<Kpi> {
    val metrics = summary != null && summary.metricsAvailable
    val cpuReq = usages.sumOf { it.cpuRequestMillis }
    val memReq = usages.sumOf { it.memoryRequestBytes }
    fun share(requested: Long, capacity: Long?): String = if (capacity != null && capacity > 0) " · ${percentNumber(requested, capacity)} % of cluster allocatable" else ""
    return listOf(
        Kpi(
            "cpu-used",
            if (metrics) "CPU ${formatCpuCores(usages.sumOf { it.cpuUsedMillis ?: 0L })} used" else "CPU used —",
            if (metrics) KpiTone.Neutral else KpiTone.Muted,
        ),
        Kpi("cpu-req", "CPU ${formatCpuCores(cpuReq)} requested" + share(cpuReq, summary?.cpuCapacityMillis)),
        Kpi(
            "mem-used",
            if (metrics) "Mem ${formatMemorySize(usages.sumOf { it.memoryUsedBytes ?: 0L })} used" else "Mem used —",
            if (metrics) KpiTone.Neutral else KpiTone.Muted,
        ),
        Kpi("mem-req", "Mem ${formatMemorySize(memReq)} requested" + share(memReq, summary?.memoryCapacityBytes)),
    )
}

/** The SparkApplication table's CPU cell: usage summed over its live pods, the requests in the tooltip; the placeholder without a sample. */
internal fun sparkCpuCell(usage: SparkAppUsage?): CellData {
    val used = usage?.cpuUsedMillis ?: return CellData(NONE_PLACEHOLDER)
    val text = formatCpuCores(used)
    return CellData(
        text = text,
        sortNumber = used.toDouble(),
        content = {
            UsageCellContent(
                text,
                "CPU",
                { "Used: $text\nRequested: ${formatCpuCores(usage.cpuRequestMillis)}\nLive pods: ${usage.livePods}" },
                UsageLevel.NORMAL,
            )
        },
    )
}

/** The memory counterpart of [sparkCpuCell]. */
internal fun sparkMemoryCell(usage: SparkAppUsage?): CellData {
    val used = usage?.memoryUsedBytes ?: return CellData(NONE_PLACEHOLDER)
    val text = formatMemorySize(used)
    return CellData(
        text = text,
        sortNumber = used.toDouble(),
        content = {
            UsageCellContent(
                text,
                "Memory",
                { "Used: $text\nRequested: ${formatMemorySize(usage.memoryRequestBytes)}\nLive pods: ${usage.livePods}" },
                UsageLevel.NORMAL,
            )
        },
    )
}

/** What the SparkApplication screen shows beside the table: each app's footprint and the strip over them. */
internal data class SparkUsageView(val byApp: Map<String, SparkAppUsage>, val kpis: List<Kpi>)

/**
 * The live Spark usage of [apps], or null while the pods are not loaded (loading, RBAC-forbidden, error): the screen then
 * shows neither the strip nor the CPU/Memory columns. Recomputes on every pods emission and every usage sample: O(pods).
 */
@Composable
internal fun rememberSparkUsage(client: ReactiveKubeClient, apps: List<GenericResourceInfo>): SparkUsageView? {
    val podsState by client.pods.collectAsState()
    val usageState by client.resourceUsage.collectAsState()
    val pods = (podsState as? ResourceState.Success)?.data
    val summary = (usageState as? ResourceState.Success)?.data
    return remember(apps, pods, summary) {
        if (pods == null) {
            null
        } else {
            val byApp = sparkAppUsages(apps, pods, summary?.podUsages.orEmpty())
            SparkUsageView(byApp, sparkKpis(byApp.values, summary))
        }
    }
}
