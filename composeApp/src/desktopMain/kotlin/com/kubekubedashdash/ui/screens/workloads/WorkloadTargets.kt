package com.kubekubedashdash.ui.screens.workloads

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.services.metricshistory.BatchUsage
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.PodSetRef
import com.kubekubedashdash.services.metricshistory.cronJobUsage
import com.kubekubedashdash.services.metricshistory.jobUsage
import com.kubekubedashdash.ui.components.ChartMarker
import java.time.ZoneId

internal const val NO_JOB_POD_PATTERN_NOTE = "This Job has no usable name for a pod pattern."

internal const val NO_CRONJOB_RUNS_NOTE = "No Job of this CronJob is loaded."

internal const val CUT_BATCH_NAME_NOTE =
    "This name is longer than a pod name keeps, so the charts can include pods of another workload whose name starts the same way."

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
    )
}

private fun batchTarget(
    res: GenericResourceInfo,
    usage: BatchUsage,
    livePods: List<PodInfo>,
    missingNote: String,
    defaultWindow: UsageWindow,
    runLabel: String,
    perPodDescription: String,
): UsageTarget {
    val grouping = UsageGrouping.PerPod(usage.currentPrefixes)
    return UsageTarget(
        key = res.uid,
        podSet = usage.ref,
        missingNote = missingNote,
        grouping = grouping,
        defaultWindow = defaultWindow,
        markers = usage.markers.map { ChartMarker(it.epochSec, it.label) },
        currentPods = livePodsOf(res.namespace, usage.ref, grouping, livePods),
        notes = if (usage.cutNames) listOf(CUT_BATCH_NAME_NOTE) else emptyList(),
        runLabel = runLabel,
        perPodDescription = perPodDescription,
    )
}

// The live pods the pattern selects that [grouping] draws in colour, sorted by name; none without a pattern.
private fun livePodsOf(namespace: String?, ref: PodSetRef?, grouping: UsageGrouping.PerPod, livePods: List<PodInfo>): List<PodInfo> {
    if (ref == null) return emptyList()
    val pattern = Regex(ref.pattern)
    return livePods
        .filter { it.namespace == namespace && pattern.matches(it.name) && grouping.isCurrent(it.name) }
        .sortedBy { it.name }
}
