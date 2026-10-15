package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.WorkloadRun
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A vertical mark on the charts at [epochSec]. */
data class RunMarker(val epochSec: Long, val label: String)

/**
 * What the usage section of a Job, CronJob, SparkApplication or ScheduledSparkApplication needs: [ref]
 * selects the pods (null when none can be named), [currentPrefixes] are the pod-name prefixes drawn in
 * colour (null: every pod is current), [markers] are time-ordered, [runStartSec] and [runEndSec] bound the
 * run the default window follows (null: unknown, or still running) and [cutNames] says a pod name cannot
 * hold the whole workload name, so the pattern may match another workload's pods.
 */
data class BatchUsage(
    val ref: PodSetRef?,
    val currentPrefixes: Set<String>?,
    val markers: List<RunMarker>,
    val runStartSec: Long?,
    val runEndSec: Long?,
    val cutNames: Boolean,
)

// A chart carries this many run markers at most, the newest ones.
private const val MARKERS_MAX = 8

// Runs of a scheduled workload older than this, counted back from its newest run, do not widen the default window.
private val RUNS_LOOKBACK_SEC = HistoryRange.D7.seconds

private const val SPARK_OPERATOR_SCHEDULED_LABEL = "sparkoperator.k8s.io/scheduled-app-name"

/** A Job's pods, with its start and end as markers; null only when [job] has no namespace. */
fun jobUsage(job: GenericResourceInfo): BatchUsage? {
    val namespace = job.namespace ?: return null
    val run = job.run ?: WorkloadRun(startTime = "", endTime = "", indexed = false)
    val startedAt = epochSecOf(run.startTime)
    val endedAt = epochSecOf(run.endTime)
    return BatchUsage(
        ref = PromQl.ofJob(namespace, job.name, run.indexed),
        currentPrefixes = null,
        markers = listOfNotNull(startedAt?.let { RunMarker(it, "start") }, endedAt?.let { RunMarker(it, "end") }).sortedBy { it.epochSec },
        runStartSec = startedAt ?: epochSecOf(job.creationTimestamp),
        runEndSec = endedAt,
        cutNames = if (run.indexed) job.name.length > PromQl.INDEXED_JOB_UNCUT_MAX else PromQl.podPrefix(job.name) != "${job.name}-",
    )
}

/**
 * The pods of the CronJob's Jobs. [jobs] may hold other Jobs too: those the CronJob owns (by uid, in its
 * namespace) are used. The newest Job's pods are current; each Job is a marker at its start, labelled with
 * its local time in [zone].
 */
fun cronJobUsage(cronJob: GenericResourceInfo, jobs: List<GenericResourceInfo>, zone: ZoneId): BatchUsage? {
    val namespace = cronJob.namespace ?: return null
    val owned = jobs
        .filter { it.namespace == namespace && it.isOwnedBy(cronJob.uid) }
        .distinctBy { it.name }
    val starts = owned.mapNotNull { startOf(it) }
    // The name is the last tie-break so the answer does not depend on the order of the informer's list.
    val newest = owned.maxWithOrNull(compareBy<GenericResourceInfo>({ startOf(it) ?: Long.MIN_VALUE }, { it.name }))
    return BatchUsage(
        ref = PromQl.ofCronJob(namespace, cronJob.name, owned.map { it.name }.sorted()),
        currentPrefixes = newest?.let { setOf(PromQl.podPrefix(it.name)) } ?: emptySet(),
        markers = runMarkers(starts, zone),
        runStartSec = runsStart(starts),
        runEndSec = null,
        cutNames = cronJob.name.length > PromQl.CRONJOB_UNCUT_MAX,
    )
}

/** A SparkApplication's driver and executors, marked where it was submitted and where it ended; the ref is null without `spark` data. */
fun sparkUsage(app: GenericResourceInfo): BatchUsage? {
    val namespace = app.namespace ?: return null
    val spark = app.spark
    val submittedAt = spark?.let { epochSecOf(it.submittedAt) }
    // The termination time of an application that is running again is from an earlier attempt.
    val endedAt = spark?.takeIf { it.finished }?.let { epochSecOf(it.terminatedAt) }
    return BatchUsage(
        ref = spark?.let { PromQl.ofSparkPods(namespace, listOf(it.driverPod), it.executorPods) },
        currentPrefixes = null,
        markers = listOfNotNull(submittedAt?.let { RunMarker(it, "submitted") }, endedAt?.let { RunMarker(it, "ended") }).sortedBy { it.epochSec },
        runStartSec = submittedAt ?: epochSecOf(app.creationTimestamp),
        runEndSec = endedAt,
        cutNames = false,
    )
}

/**
 * The pods of a ScheduledSparkApplication's runs. [runs] may hold other applications too: those it owns
 * (by uid, else by the operator's `scheduled-app-name` label, in its namespace) are used. Each run is a
 * marker at its submission, labelled with its local time in [zone].
 */
fun scheduledSparkUsage(scheduled: GenericResourceInfo, runs: List<GenericResourceInfo>, zone: ZoneId): BatchUsage? {
    val namespace = scheduled.namespace ?: return null
    val owned = ownedSparkRuns(scheduled, runs)
    val sparkRuns = owned.mapNotNull { it.spark }
    val starts = owned.mapNotNull { submissionOf(it) }
    return BatchUsage(
        ref = PromQl.ofSparkPods(namespace, sparkRuns.map { it.driverPod }, sparkRuns.flatMap { it.executorPods }),
        currentPrefixes = null,
        markers = runMarkers(starts, zone),
        runStartSec = runsStart(starts),
        runEndSec = null,
        cutNames = false,
    )
}

/**
 * The runs of [scheduled] among [runs], which may hold other applications too: those in its namespace that
 * it owns by uid, else that carry the operator's `scheduled-app-name` label with its name.
 */
internal fun ownedSparkRuns(scheduled: GenericResourceInfo, runs: List<GenericResourceInfo>): List<GenericResourceInfo> {
    val namespace = scheduled.namespace ?: return emptyList()
    return runs
        .filter { it.namespace == namespace && (it.isOwnedBy(scheduled.uid) || it.labels[SPARK_OPERATOR_SCHEDULED_LABEL] == scheduled.name) }
        .distinctBy { it.name }
}

private fun GenericResourceInfo.isOwnedBy(uid: String): Boolean = uid.isNotBlank() && owners.any { it.uid == uid }

private fun startOf(job: GenericResourceInfo): Long? = epochSecOf(job.run?.startTime.orEmpty()) ?: epochSecOf(job.creationTimestamp)

private fun submissionOf(app: GenericResourceInfo): Long? = epochSecOf(app.spark?.submittedAt.orEmpty()) ?: epochSecOf(app.creationTimestamp)

// The newest MARKERS_MAX runs, oldest first.
private fun runMarkers(startsSec: List<Long>, zone: ZoneId): List<RunMarker> {
    val format = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone)
    return startsSec.sorted().takeLast(MARKERS_MAX).map { RunMarker(it, format.format(Instant.ofEpochSecond(it))) }
}

// The oldest run's start, but not more than a week before the newest run's.
private fun runsStart(startsSec: List<Long>): Long? {
    val newest = startsSec.maxOrNull() ?: return null
    return maxOf(startsSec.min(), newest - RUNS_LOOKBACK_SEC)
}

// A blank or unreadable timestamp is no timestamp.
private fun epochSecOf(timestamp: String): Long? = if (timestamp.isBlank()) {
    null
} else {
    try {
        Instant.parse(timestamp).epochSecond
    } catch (_: DateTimeException) {
        null
    }
}
