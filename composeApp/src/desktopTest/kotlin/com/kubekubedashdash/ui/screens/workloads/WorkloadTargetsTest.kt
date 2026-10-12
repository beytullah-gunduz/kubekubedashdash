package com.kubekubedashdash.ui.screens.workloads

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.SparkRun
import com.kubekubedashdash.models.WorkloadRun
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.ui.components.ChartMarker
import com.kubekubedashdash.util.ShellEnvironment
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NAMESPACE = "team-a"

private const val CRON_UID = "uid-cron-1"

// 2026-10-12T08:00:00Z, a whole minute.
private const val T0 = 1_791_792_000L

/** The pure builders behind the usage sections of the Job, CronJob, SparkApplication and ScheduledSparkApplication panels. */
class WorkloadTargetsTest {

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

    private fun iso(epochSec: Long) = Instant.ofEpochSecond(epochSec).toString()

    private fun row(
        name: String,
        uid: String = "uid-$name",
        namespace: String? = NAMESPACE,
        created: String = "",
        owners: List<OwnerRefInfo> = emptyList(),
        run: WorkloadRun? = null,
        labels: Map<String, String> = emptyMap(),
        spark: SparkRun? = null,
    ) = GenericResourceInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        status = null,
        age = "1m",
        labels = labels,
        annotations = emptyMap(),
        owners = owners,
        creationTimestamp = created,
        run = run,
        spark = spark,
    )

    private fun job(
        name: String,
        start: Long? = null,
        end: Long? = null,
        indexed: Boolean = false,
        namespace: String? = NAMESPACE,
        owners: List<OwnerRefInfo> = emptyList(),
    ) = row(
        name = name,
        namespace = namespace,
        owners = owners,
        run = WorkloadRun(startTime = start?.let(::iso).orEmpty(), endTime = end?.let(::iso).orEmpty(), indexed = indexed),
    )

    private fun ownedByCron() = listOf(OwnerRefInfo(kind = "CronJob", name = "nightly-backup", uid = CRON_UID, controller = true))

    private fun pod(name: String, namespace: String = NAMESPACE) = PodInfo(
        uid = "uid-$namespace-$name",
        name = name,
        namespace = namespace,
        status = "Running",
        ready = "1/1",
        restarts = 0,
        age = "1m",
        node = "",
        ip = "",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = emptyList(),
    )

    // ── Job ─────────────────────────────────────────────────────────────────

    @Test
    fun `a finished Job is charted over its run, with its start and end marked`() {
        val res = job("db-vacuum", start = T0, end = T0 + 330)

        val target = assertNotNull(jobTarget(res, emptyList()))

        assertEquals("uid-db-vacuum", target.key)
        assertEquals(PromQl.ofJob(NAMESPACE, "db-vacuum", indexed = false), target.podSet)
        assertEquals(UsageGrouping.PerPod(null), target.grouping)
        assertEquals(UsageWindow.Run(T0, T0 + 330), target.defaultWindow)
        assertEquals(listOf(ChartMarker(T0, "start"), ChartMarker(T0 + 330, "end")), target.markers)
        assertEquals("Run", target.runLabel)
        assertEquals("One line per pod.", target.perPodDescription)
        assertEquals(emptyList(), target.notes)
    }

    @Test
    fun `a running Job's run has no end, and only its start is marked`() {
        val target = assertNotNull(jobTarget(job("db-vacuum", start = T0), emptyList()))

        assertEquals(UsageWindow.Run(T0, null), target.defaultWindow)
        assertEquals(listOf(ChartMarker(T0, "start")), target.markers)
    }

    @Test
    fun `a Job that has not started follows its creation time, with no marker`() {
        val target = assertNotNull(jobTarget(row("db-vacuum", created = iso(T0 - 60), run = WorkloadRun("", "", indexed = false)), emptyList()))

        assertEquals(UsageWindow.Run(T0 - 60, null), target.defaultWindow)
        assertEquals(emptyList(), target.markers)
    }

    @Test
    fun `a Job with no known time falls back to the last 6 hours, under the fixed ranges only`() {
        val target = assertNotNull(jobTarget(row("db-vacuum"), emptyList()))

        assertEquals(UsageWindow.Sliding(HistoryRange.H6), target.defaultWindow)
        assertEquals(emptyList(), target.markers)
    }

    @Test
    fun `a Job without a namespace has no section`() {
        assertNull(jobTarget(job("db-vacuum", start = T0, namespace = null), emptyList()))
    }

    @Test
    fun `every live pod of the Job is current, retries included, in name order`() {
        val res = job("db-vacuum", start = T0)
        val livePods = listOf(
            pod("db-vacuum-x7k2p"),
            pod("db-vacuum-92f6w"),
            // Another namespace, another Job whose name starts the same way, and a pod of another Job's retry.
            pod("db-vacuum-h4m8q", namespace = "team-b"),
            pod("db-vacuum-extra-c3n5v"),
            pod("log-rotation-d9b2k"),
        )

        val target = assertNotNull(jobTarget(res, livePods))

        assertEquals(listOf("db-vacuum-92f6w", "db-vacuum-x7k2p"), target.currentPods.map { it.name })
    }

    @Test
    fun `the pods of an Indexed Job are matched at every index`() {
        val res = job("db-vacuum", start = T0, indexed = true)
        val livePods = listOf(pod("db-vacuum-1-x7k2p"), pod("db-vacuum-0-92f6w"), pod("db-vacuum-x7k2p"))

        val target = assertNotNull(jobTarget(res, livePods))

        assertEquals(listOf("db-vacuum-0-92f6w", "db-vacuum-1-x7k2p"), target.currentPods.map { it.name })
    }

    @Test
    fun `a Job name that no pod pattern can carry says so, and has no current pod`() {
        val target = assertNotNull(jobTarget(job("Db_Vacuum", start = T0), listOf(pod("db-vacuum-x7k2p"))))

        assertNull(target.podSet)
        assertEquals("This Job has no usable name for a pod pattern.", target.missingNote)
        assertEquals(emptyList(), target.currentPods)
    }

    @Test
    fun `a Job name longer than a pod name keeps adds a note that other workloads may be included`() {
        val name = "a-job-whose-name-is-much-longer-than-a-generated-pod-name-keeps-" + "x"
        val note = "This name is longer than a pod name keeps, so the charts can include pods of another workload whose name starts the same way."

        assertEquals(listOf(note), assertNotNull(jobTarget(job(name, start = T0), emptyList())).notes)
        assertEquals(emptyList(), assertNotNull(jobTarget(job("db-vacuum", start = T0), emptyList())).notes)
    }

    // ── CronJob ─────────────────────────────────────────────────────────────

    private val cron = row("nightly-backup", uid = CRON_UID)

    private val oldest = job("nightly-backup-29123400", start = T0, end = T0 + 300, owners = ownedByCron())
    private val middle = job("nightly-backup-29123460", start = T0 + 3_600, end = T0 + 3_900, owners = ownedByCron())
    private val newest = job("nightly-backup-29123520", start = T0 + 7_200, owners = ownedByCron())
    private val stranger = job("log-rotation-29123520", start = T0 - 86_400)

    @Test
    fun `a CronJob is charted from its oldest run, with a marker at each run's local start time`() {
        val target = assertNotNull(cronJobTarget(cron, listOf(newest, stranger, oldest, middle), emptyList(), ZoneOffset.UTC))

        assertEquals(CRON_UID, target.key)
        assertEquals(PromQl.ofCronJob(NAMESPACE, "nightly-backup", listOf(oldest.name, middle.name, newest.name)), target.podSet)
        assertEquals(UsageWindow.Runs(T0), target.defaultWindow)
        assertEquals(listOf(ChartMarker(T0, "08:00"), ChartMarker(T0 + 3_600, "09:00"), ChartMarker(T0 + 7_200, "10:00")), target.markers)
        assertEquals("Runs", target.runLabel)
        assertEquals("One line per pod; earlier runs in grey.", target.perPodDescription)
        assertEquals(emptyList(), target.notes)
    }

    @Test
    fun `the run markers are labelled in the zone it is given`() {
        val target = assertNotNull(cronJobTarget(cron, listOf(oldest, newest), emptyList(), ZoneOffset.ofHoursMinutes(5, 30)))

        assertEquals(listOf("13:30", "15:30"), target.markers.map { it.label })
    }

    @Test
    fun `only the pods of the newest Job are current, the pods of earlier runs are not`() {
        val livePods = listOf(
            pod("nightly-backup-29123520-x7k2p"),
            pod("nightly-backup-29123460-92f6w"),
            pod("nightly-backup-29123520-h4m8q"),
            pod("nightly-backup-29123520-c3n5v", namespace = "team-b"),
            pod("log-rotation-29123520-d9b2k"),
        )

        val target = assertNotNull(cronJobTarget(cron, listOf(oldest, middle, newest), livePods, ZoneOffset.UTC))

        assertEquals(UsageGrouping.PerPod(setOf("nightly-backup-29123520-")), target.grouping)
        assertEquals(listOf("nightly-backup-29123520-h4m8q", "nightly-backup-29123520-x7k2p"), target.currentPods.map { it.name })
    }

    @Test
    fun `a CronJob whose Jobs are not loaded is charted by name pattern over the last 24 hours, with no current pod`() {
        val target = assertNotNull(cronJobTarget(cron, emptyList(), listOf(pod("nightly-backup-29123520-x7k2p")), ZoneOffset.UTC))

        assertEquals(PromQl.ofCronJob(NAMESPACE, "nightly-backup", emptyList()), target.podSet)
        assertEquals(UsageGrouping.PerPod(emptySet()), target.grouping)
        assertEquals(UsageWindow.Sliding(HistoryRange.H24), target.defaultWindow)
        assertEquals(emptyList(), target.markers)
        assertEquals(emptyList(), target.currentPods)
    }

    @Test
    fun `a CronJob name too long to keep its Jobs' names whole needs a loaded Job, and says so`() {
        val name = "a-cron-job-whose-name-is-long-enough-to-cut-its-jobs-names"
        val longCron = row(name, uid = CRON_UID)
        val note = "This name is longer than a pod name keeps, so the charts can include pods of another workload whose name starts the same way."

        val without = assertNotNull(cronJobTarget(longCron, emptyList(), emptyList(), ZoneOffset.UTC))
        assertNull(without.podSet)
        assertEquals("No Job of this CronJob is loaded.", without.missingNote)
        assertEquals(listOf(note), without.notes)

        val withJob = assertNotNull(cronJobTarget(longCron, listOf(job("$name-29123520", start = T0, owners = ownedByCron())), emptyList(), ZoneOffset.UTC))
        assertNotNull(withJob.podSet)
        assertTrue(withJob.notes.isNotEmpty())
    }

    @Test
    fun `a CronJob without a namespace has no section`() {
        assertNull(cronJobTarget(row("nightly-backup", uid = CRON_UID, namespace = null), emptyList(), emptyList(), ZoneOffset.UTC))
    }

    // ── SparkApplication ────────────────────────────────────────────────────

    private val driver = "spark-etl-nightly-driver"
    private val executors = listOf("spark-etl-nightly-1a2b3c4d5e6f7890-exec-1", "spark-etl-nightly-1a2b3c4d5e6f7890-exec-2")

    private fun sparkApp(
        name: String = "spark-etl-nightly",
        state: String = "COMPLETED",
        submitted: Long? = T0,
        terminated: Long? = T0 + 1_200,
        driverPod: String = driver,
        executorPods: List<String> = executors,
        created: String = "",
        namespace: String? = NAMESPACE,
        labels: Map<String, String> = emptyMap(),
        owners: List<OwnerRefInfo> = emptyList(),
    ) = row(
        name = name,
        namespace = namespace,
        created = created,
        labels = labels,
        owners = owners,
        spark = SparkRun(
            driverPod = driverPod,
            executorPods = executorPods,
            submittedAt = submitted?.let(::iso).orEmpty(),
            terminatedAt = terminated?.let(::iso).orEmpty(),
            state = state,
        ),
    )

    @Test
    fun `a completed SparkApplication is charted over its run as a driver and its executors, with its submission and end marked`() {
        val target = assertNotNull(sparkTarget(sparkApp(), emptyList()))

        assertEquals("uid-spark-etl-nightly", target.key)
        assertEquals(PromQl.ofExactPods(NAMESPACE, listOf(driver) + executors), target.podSet)
        assertEquals(UsageGrouping.DriverExecutors(setOf(driver)), target.grouping)
        assertEquals(UsageWindow.Run(T0, T0 + 1_200), target.defaultWindow)
        assertEquals(listOf(ChartMarker(T0, "submitted"), ChartMarker(T0 + 1_200, "ended")), target.markers)
        assertEquals("Run", target.runLabel)
        assertEquals("This application's status names no pods yet.", target.missingNote)
        assertEquals(emptyList(), target.notes)
    }

    @Test
    fun `a running SparkApplication's run has no end, even when an earlier attempt left a termination time`() {
        val target = assertNotNull(sparkTarget(sparkApp(state = "RUNNING", terminated = T0 - 600), emptyList()))

        assertEquals(UsageWindow.Run(T0, null), target.defaultWindow)
        assertEquals(listOf(ChartMarker(T0, "submitted")), target.markers)
    }

    @Test
    fun `a failed SparkApplication ends its run too`() {
        val target = assertNotNull(sparkTarget(sparkApp(name = "spark-failed-job", state = "FAILED"), emptyList()))

        assertEquals(UsageWindow.Run(T0, T0 + 1_200), target.defaultWindow)
        assertEquals(listOf("submitted", "ended"), target.markers.map { it.label })
    }

    @Test
    fun `the driver line follows the driver pod the status names`() {
        val target = assertNotNull(sparkTarget(sparkApp(driverPod = "spark-etl-nightly-custom-driver"), emptyList()))

        assertEquals(UsageGrouping.DriverExecutors(setOf("spark-etl-nightly-custom-driver")), target.grouping)
    }

    @Test
    fun `a SparkApplication whose status names no pods says so, follows its creation time and has no current pod`() {
        val target = assertNotNull(sparkTarget(row("spark-etl-nightly", created = iso(T0 - 60)), listOf(pod(driver))))

        assertNull(target.podSet)
        assertEquals("This application's status names no pods yet.", target.missingNote)
        assertEquals(UsageGrouping.DriverExecutors(emptySet()), target.grouping)
        assertEquals(UsageWindow.Run(T0 - 60, null), target.defaultWindow)
        assertEquals(emptyList(), target.markers)
        assertEquals(emptyList(), target.currentPods)
    }

    @Test
    fun `a SparkApplication with no known time falls back to the last 6 hours`() {
        val target = assertNotNull(sparkTarget(row("spark-etl-nightly"), emptyList()))

        assertEquals(UsageWindow.Sliding(HistoryRange.H6), target.defaultWindow)
    }

    @Test
    fun `the current pods of a SparkApplication are the live pods of its driver and executors, in name order`() {
        val livePods = listOf(
            pod(executors[1]),
            pod(driver),
            pod(executors[0]),
            // Another namespace, another application whose name starts the same way, and an executor of an earlier submission.
            pod(driver, namespace = "team-b"),
            pod("spark-etl-nightly-extra-driver"),
            pod("spark-etl-nightly-0f9e8d7c6b5a4321-exec-1"),
        )

        val target = assertNotNull(sparkTarget(sparkApp(), livePods))

        assertEquals(listOf(executors[0], executors[1], driver), target.currentPods.map { it.name })
        assertTrue(target.currentPods.all { it.namespace == NAMESPACE })
    }

    @Test
    fun `a SparkApplication with many executors matches its live executors by stem`() {
        val many = (1..80).map { "spark-etl-nightly-1a2b3c4d5e6f7890-exec-$it" }
        val livePods = listOf(pod(driver), pod(many.last()), pod("spark-etl-nightly-0f9e8d7c6b5a4321-exec-1"))

        val target = assertNotNull(sparkTarget(sparkApp(executorPods = many), livePods))

        assertEquals(listOf(many.last(), driver), target.currentPods.map { it.name })
    }

    @Test
    fun `a SparkApplication without a namespace has no section`() {
        assertNull(sparkTarget(sparkApp(namespace = null), emptyList()))
    }

    // ── ScheduledSparkApplication ───────────────────────────────────────────

    private val scheduledUid = "uid-sched-1"
    private val scheduledLabel = "sparkoperator.k8s.io/scheduled-app-name"
    private val scheduled = row("spark-report-hourly", uid = scheduledUid)

    private fun ownedByScheduled(uid: String = scheduledUid) = listOf(OwnerRefInfo(kind = "ScheduledSparkApplication", name = "spark-report-hourly", uid = uid, controller = true))

    private fun runDriver(suffix: String) = "spark-report-hourly-$suffix-driver"

    private fun runExecutor(suffix: String) = "spark-report-hourly-$suffix-1a2b3c4d5e6f7890-exec-1"

    private fun scheduledRun(
        suffix: String,
        submitted: Long,
        ownerUid: String? = scheduledUid,
        label: String? = null,
        namespace: String? = NAMESPACE,
    ) = sparkApp(
        name = "spark-report-hourly-$suffix",
        submitted = submitted,
        terminated = submitted + 300,
        driverPod = runDriver(suffix),
        executorPods = listOf(runExecutor(suffix)),
        namespace = namespace,
        labels = if (label != null) mapOf(scheduledLabel to label) else emptyMap(),
        owners = if (ownerUid != null) ownedByScheduled(ownerUid) else emptyList(),
    )

    private val firstRun = scheduledRun("1760252400", submitted = T0)
    private val secondRun = scheduledRun("1760256000", submitted = T0 + 3_600)

    @Test
    fun `a ScheduledSparkApplication is charted from its oldest run, with a marker at each run's local start time`() {
        val target = assertNotNull(scheduledSparkTarget(scheduled, listOf(secondRun, firstRun), emptyList(), ZoneOffset.UTC))

        assertEquals(scheduledUid, target.key)
        assertEquals(
            PromQl.ofExactPods(NAMESPACE, listOf(runDriver("1760252400"), runExecutor("1760252400"), runDriver("1760256000"), runExecutor("1760256000"))),
            target.podSet,
        )
        assertEquals(UsageGrouping.DriverExecutors(setOf(runDriver("1760252400"), runDriver("1760256000"))), target.grouping)
        assertEquals(UsageWindow.Runs(T0), target.defaultWindow)
        assertEquals(listOf(ChartMarker(T0, "08:00"), ChartMarker(T0 + 3_600, "09:00")), target.markers)
        assertEquals("Runs", target.runLabel)
        assertEquals("No run of this application is loaded.", target.missingNote)
        assertEquals(emptyList(), target.notes)
    }

    @Test
    fun `the run markers of a ScheduledSparkApplication are labelled in the zone it is given`() {
        val target = assertNotNull(scheduledSparkTarget(scheduled, listOf(firstRun, secondRun), emptyList(), ZoneOffset.ofHoursMinutes(5, 30)))

        assertEquals(listOf("13:30", "14:30"), target.markers.map { it.label })
    }

    @Test
    fun `a run counts by the operator's label when its owner is missing or another uid, and only in the namespace of the scheduled application`() {
        val runs = listOf(
            firstRun,
            scheduledRun("1760256000", submitted = T0 + 3_600, ownerUid = null, label = "spark-report-hourly"),
            scheduledRun("1760259600", submitted = T0 + 7_200, ownerUid = "uid-sched-2", label = "spark-report-hourly"),
            scheduledRun("1760263200", submitted = T0 + 10_800, ownerUid = "uid-sched-2"),
            scheduledRun("1760266800", submitted = T0 + 14_400, ownerUid = null, label = "spark-report-daily"),
            scheduledRun("1760270400", submitted = T0 + 18_000, namespace = "team-b"),
            scheduledRun("1760274000", submitted = T0 + 21_600, namespace = null),
            scheduledRun("1760277600", submitted = T0 + 25_200, ownerUid = null),
        )

        val target = assertNotNull(scheduledSparkTarget(scheduled, runs, emptyList(), ZoneOffset.UTC))

        assertEquals(listOf("08:00", "09:00", "10:00"), target.markers.map { it.label })
        assertEquals(UsageGrouping.DriverExecutors(setOf("1760252400", "1760256000", "1760259600").map(::runDriver).toSet()), target.grouping)
    }

    @Test
    fun `a run row without Spark data is marked but adds no driver`() {
        val withoutSpark = row("spark-report-hourly-1760256000", owners = ownedByScheduled(), created = iso(T0 + 3_600))

        val target = assertNotNull(scheduledSparkTarget(scheduled, listOf(firstRun, withoutSpark), emptyList(), ZoneOffset.UTC))

        assertEquals(UsageGrouping.DriverExecutors(setOf(runDriver("1760252400"))), target.grouping)
        assertEquals(listOf("08:00", "09:00"), target.markers.map { it.label })
    }

    @Test
    fun `the current pods of a ScheduledSparkApplication are the live pods of its runs, in name order`() {
        val livePods = listOf(
            pod(runExecutor("1760256000")),
            pod(runDriver("1760252400")),
            pod(runDriver("1760256000")),
            pod(runDriver("1760256000"), namespace = "team-b"),
            pod(runDriver("1760259600")),
            pod("spark-etl-nightly-driver"),
        )

        val target = assertNotNull(scheduledSparkTarget(scheduled, listOf(firstRun, secondRun), livePods, ZoneOffset.UTC))

        assertEquals(listOf(runDriver("1760252400"), runExecutor("1760256000"), runDriver("1760256000")), target.currentPods.map { it.name })
    }

    @Test
    fun `a ScheduledSparkApplication with no run loaded says so and is charted over the last 24 hours`() {
        val target = assertNotNull(scheduledSparkTarget(scheduled, emptyList(), listOf(pod(runDriver("1760252400"))), ZoneOffset.UTC))

        assertNull(target.podSet)
        assertEquals("No run of this application is loaded.", target.missingNote)
        assertEquals(UsageGrouping.DriverExecutors(emptySet()), target.grouping)
        assertEquals(UsageWindow.Sliding(HistoryRange.H24), target.defaultWindow)
        assertEquals(emptyList(), target.markers)
        assertEquals(emptyList(), target.currentPods)
    }

    @Test
    fun `a ScheduledSparkApplication whose runs all belong to others has no run either`() {
        val target = assertNotNull(scheduledSparkTarget(scheduled, listOf(scheduledRun("1760252400", submitted = T0, ownerUid = "uid-sched-2")), emptyList(), ZoneOffset.UTC))

        assertNull(target.podSet)
        assertEquals(UsageWindow.Sliding(HistoryRange.H24), target.defaultWindow)
    }

    @Test
    fun `a ScheduledSparkApplication without a namespace has no section`() {
        assertNull(scheduledSparkTarget(row("spark-report-hourly", uid = scheduledUid, namespace = null), emptyList(), emptyList(), ZoneOffset.UTC))
    }
}
