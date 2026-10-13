package com.kubekubedashdash.ui.screens.workloads

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.services.metricshistory.BatchUsage
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PodSetMode
import com.kubekubedashdash.services.metricshistory.PodSetRef
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.cronJobUsage
import com.kubekubedashdash.services.metricshistory.jobUsage
import com.kubekubedashdash.services.metricshistory.ownedSparkRuns
import com.kubekubedashdash.services.metricshistory.scheduledSparkUsage
import com.kubekubedashdash.services.metricshistory.sparkUsage
import com.kubekubedashdash.ui.components.ChartMarker
import com.kubekubedashdash.ui.components.ChartReference
import com.kubekubedashdash.util.QuotaCeilings
import java.time.ZoneId

internal const val NO_JOB_POD_PATTERN_NOTE = "This Job has no usable name for a pod pattern."

internal const val NO_CRONJOB_RUNS_NOTE = "No Job of this CronJob is loaded."

internal const val NO_SPARK_PODS_NOTE = "This application's status names no pods yet."

internal const val NO_SCHEDULED_SPARK_RUNS_NOTE = "No run of this application is loaded."

internal const val CUT_BATCH_NAME_NOTE =
    "This name is longer than a pod name keeps, so the charts can include pods of another workload whose name starts the same way."

internal const val JOB_NO_CPU_LIMIT_NOTE = "No pod of this Job has a CPU limit — CFS throttling does not apply."

internal const val CRONJOB_NO_CPU_LIMIT_NOTE = "No pod of the newest run has a CPU limit — CFS throttling does not apply."

internal const val SPARK_NO_CPU_LIMIT_NOTE = "No pod of this application has a CPU limit — CFS throttling does not apply."

internal const val SCHEDULED_SPARK_NO_CPU_LIMIT_NOTE = "No pod of these runs has a CPU limit — CFS throttling does not apply."

internal const val BATCH_GMP_STALENESS_NOTE =
    "Managed Prometheus keeps reporting a deleted pod for a few minutes (memory up to 5, CPU for its rate window, at least 5), so a pod's line can run past the end of its run."

internal const val NAMESPACE_MISSING_NOTE = "This namespace name cannot be used in a query."

internal const val NAMESPACE_GMP_STALENESS_NOTE =
    "Managed Prometheus keeps reporting a deleted pod for a few minutes (memory up to 5, CPU for its rate window, at least 5), so Total can count a replaced pod twice for that long."

internal const val NAMESPACE_HOST_NETWORK_NOTE =
    "Some pods of this namespace use the host network: their counters are their node's, so the namespace's network is not charted."

internal const val NAMESPACE_HOST_NETWORK_PENDING_NOTE = "Checking whether pods of this namespace use the host network…"

/**
 * What a Job's usage section charts; null only when [res] has no namespace. Every pod of the Job is current,
 * retries included, and the run is the default window.
 */
internal fun jobTarget(res: GenericResourceInfo, livePods: List<PodInfo>): UsageTarget? {
    val usage = jobUsage(res) ?: return null
    return batchTarget(
        res = res,
        usage = usage,
        livePods = livePods,
        missingNote = NO_JOB_POD_PATTERN_NOTE,
        defaultWindow = usage.runStartSec?.let { UsageWindow.Run(it, usage.runEndSec) } ?: UsageWindow.Sliding(HistoryRange.H6),
        runLabel = "Run",
        perPodDescription = "One line per pod.",
        noCpuLimitNote = JOB_NO_CPU_LIMIT_NOTE,
    )
}

/**
 * What a CronJob's usage section charts; null only when [res] has no namespace. [jobs] are the CronJob's
 * Jobs (others are ignored). The pods of the newest Job are current and those of earlier runs are grey
 * lines; the runs from the oldest one on are the default window.
 */
internal fun cronJobTarget(res: GenericResourceInfo, jobs: List<GenericResourceInfo>, livePods: List<PodInfo>, zone: ZoneId): UsageTarget? {
    val usage = cronJobUsage(res, jobs, zone) ?: return null
    return batchTarget(
        res = res,
        usage = usage,
        livePods = livePods,
        missingNote = NO_CRONJOB_RUNS_NOTE,
        defaultWindow = usage.runStartSec?.let { UsageWindow.Runs(it) } ?: UsageWindow.Sliding(HistoryRange.H24),
        runLabel = "Runs",
        perPodDescription = "One line per pod; earlier runs in grey.",
        noCpuLimitNote = CRONJOB_NO_CPU_LIMIT_NOTE,
    )
}

/**
 * What a SparkApplication's usage section charts; null only when [res] has no namespace. The driver is one
 * line and the executors another, and the run (submission to termination, or to the present) is the default window.
 */
internal fun sparkTarget(res: GenericResourceInfo, livePods: List<PodInfo>): UsageTarget? {
    val usage = sparkUsage(res) ?: return null
    return driverExecutorsTarget(
        res = res,
        usage = usage,
        livePods = livePods,
        missingNote = NO_SPARK_PODS_NOTE,
        driverPods = res.spark?.let { setOf(it.driverPod) }.orEmpty(),
        defaultWindow = usage.runStartSec?.let { UsageWindow.Run(it, usage.runEndSec) } ?: UsageWindow.Sliding(HistoryRange.H6),
        runLabel = "Run",
        noCpuLimitNote = SPARK_NO_CPU_LIMIT_NOTE,
    )
}

/**
 * What a ScheduledSparkApplication's usage section charts; null only when [res] has no namespace. [runs] are
 * SparkApplications (others are ignored); the drivers of the ones it owns are the driver line, and the runs
 * from the oldest one on are the default window.
 */
internal fun scheduledSparkTarget(res: GenericResourceInfo, runs: List<GenericResourceInfo>, livePods: List<PodInfo>, zone: ZoneId): UsageTarget? {
    val usage = scheduledSparkUsage(res, runs, zone) ?: return null
    return driverExecutorsTarget(
        res = res,
        usage = usage,
        livePods = livePods,
        missingNote = NO_SCHEDULED_SPARK_RUNS_NOTE,
        driverPods = ownedSparkRuns(res, runs).mapNotNull { it.spark?.driverPod }.toSet(),
        defaultWindow = usage.runStartSec?.let { UsageWindow.Runs(it) } ?: UsageWindow.Sliding(HistoryRange.H24),
        runLabel = "Runs",
        noCpuLimitNote = SCHEDULED_SPARK_NO_CPU_LIMIT_NOTE,
    )
}

/**
 * What a Namespace's usage section charts: every pod of the namespace, summed first, with the quota hard values
 * in [ceilings] as guide lines in that sum. Never null: a name that cannot be queried gives no pod set and the
 * section says so. Per pod has no guide lines, as no one pod stands for the namespace. A host-network pod's
 * network counters are its node's, so when [hostNetwork] is true the network is not charted; null (not
 * known) charts it. While [hostNetworkPending], the network waits for that answer and says so.
 */
internal fun namespaceTarget(
    namespace: GenericResourceInfo,
    ceilings: QuotaCeilings,
    hostNetwork: Boolean? = null,
    hostNetworkPending: Boolean = false,
): UsageTarget = UsageTarget(
    key = namespace.uid.ifBlank { "Namespace/" + namespace.name },
    podSet = PromQl.ofNamespace(namespace.name),
    missingNote = NAMESPACE_MISSING_NOTE,
    grouping = UsageGrouping.PerPod(currentPrefixes = null),
    defaultWindow = UsageWindow.Sliding(HistoryRange.H6),
    markers = emptyList(),
    currentPods = emptyList(),
    notes = emptyList(),
    runLabel = "",
    perPodDescription = "One line per pod; the biggest consumers in colour.",
    stalenessNote = NAMESPACE_GMP_STALENESS_NOTE,
    throttlingWithoutLivePods = true,
    defaultMode = PodSetMode.Total,
    totalReferences = mapOf(
        PodSeriesKind.Cpu to quotaReferences(ceilings.requestsCpu, ceilings.limitsCpu),
        PodSeriesKind.Memory to quotaReferences(ceilings.requestsMemory, ceilings.limitsMemory),
    ).filterValues { it.isNotEmpty() },
    networkExcludedNote = when {
        hostNetworkPending -> NAMESPACE_HOST_NETWORK_PENDING_NOTE
        hostNetwork == true -> NAMESPACE_HOST_NETWORK_NOTE
        else -> null
    },
)

// They bound the sum of the pods' requests or limits, not their usage, so usage above the requests line is normal.
private fun quotaReferences(requests: Double?, limits: Double?): List<ChartReference> = listOfNotNull(
    requests?.let { ChartReference("quota requests", it, dashed = true) },
    limits?.let { ChartReference("quota limits", it, dashed = false) },
)

private fun batchTarget(
    res: GenericResourceInfo,
    usage: BatchUsage,
    livePods: List<PodInfo>,
    missingNote: String,
    defaultWindow: UsageWindow,
    runLabel: String,
    perPodDescription: String,
    noCpuLimitNote: String,
): UsageTarget {
    val grouping = UsageGrouping.PerPod(usage.currentPrefixes)
    return UsageTarget(
        key = res.uid,
        podSet = usage.ref,
        missingNote = missingNote,
        grouping = grouping,
        defaultWindow = defaultWindow,
        markers = usage.markers.map { ChartMarker(it.epochSec, it.label) },
        currentPods = livePodsOf(res.namespace, usage.ref, livePods, grouping::isCurrent),
        notes = if (usage.cutNames) listOf(CUT_BATCH_NAME_NOTE) else emptyList(),
        runLabel = runLabel,
        perPodDescription = perPodDescription,
        noCpuLimitNote = noCpuLimitNote,
        stalenessNote = BATCH_GMP_STALENESS_NOTE,
        throttlingWithoutLivePods = true,
    )
}

// Every live pod the pattern selects is current; they decide which charts apply (network, throttling), and a driver and executors get no request or limit line.
private fun driverExecutorsTarget(
    res: GenericResourceInfo,
    usage: BatchUsage,
    livePods: List<PodInfo>,
    missingNote: String,
    driverPods: Set<String>,
    defaultWindow: UsageWindow,
    runLabel: String,
    noCpuLimitNote: String,
): UsageTarget = UsageTarget(
    key = res.uid,
    podSet = usage.ref,
    missingNote = missingNote,
    grouping = UsageGrouping.DriverExecutors(driverPods),
    defaultWindow = defaultWindow,
    markers = usage.markers.map { ChartMarker(it.epochSec, it.label) },
    currentPods = livePodsOf(res.namespace, usage.ref, livePods),
    notes = emptyList(),
    runLabel = runLabel,
    perPodDescription = "",
    noCpuLimitNote = noCpuLimitNote,
    stalenessNote = BATCH_GMP_STALENESS_NOTE,
    throttlingWithoutLivePods = true,
)

// The live pods the pattern selects that [isCurrent] draws in colour, sorted by name; none without a pattern.
private fun livePodsOf(namespace: String?, ref: PodSetRef?, livePods: List<PodInfo>, isCurrent: (String) -> Boolean = { true }): List<PodInfo> {
    if (ref == null) return emptyList()
    val pattern = Regex(ref.pattern)
    return livePods
        .filter { it.namespace == namespace && pattern.matches(it.name) && isCurrent(it.name) }
        .sortedBy { it.name }
}
