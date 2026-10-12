package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.SparkRun
import com.kubekubedashdash.models.WorkloadRun
import com.kubekubedashdash.util.ShellEnvironment
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the usage section of a Job, CronJob, SparkApplication or ScheduledSparkApplication is built from. */
class BatchPodSetsTest {

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

    private fun epoch(timestamp: String) = Instant.parse(timestamp).epochSecond

    private fun row(
        name: String,
        namespace: String? = "default",
        uid: String = "uid-$name",
        created: String = "",
        labels: Map<String, String> = emptyMap(),
        owners: List<OwnerRefInfo> = emptyList(),
        run: WorkloadRun? = null,
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

    private fun ownedBy(uid: String, kind: String = "CronJob") = listOf(OwnerRefInfo(kind = kind, name = "owner", uid = uid, controller = true))

    // ── Job ─────────────────────────────────────────────────────────────────────

    private val cronUid = "uid-cron-1"

    private fun job(name: String, start: String, end: String = "", indexed: Boolean = false, owners: List<OwnerRefInfo> = emptyList(), namespace: String? = "default", created: String = "") = row(
        name = name,
        namespace = namespace,
        owners = owners,
        created = created,
        run = WorkloadRun(startTime = start, endTime = end, indexed = indexed),
    )

    @Test
    fun `a finished Job marks its start and its end and bounds the run`() {
        val usage = jobUsage(job("nightly-backup-29123456", start = "2026-10-12T08:00:00Z", end = "2026-10-12T08:05:30Z"))

        assertNotNull(usage)
        assertEquals(PromQl.ofJob("default", "nightly-backup-29123456", indexed = false), usage.ref)
        assertNull(usage.currentPrefixes, "every pod of a Job is current")
        assertEquals(
            listOf(RunMarker(epoch("2026-10-12T08:00:00Z"), "start"), RunMarker(epoch("2026-10-12T08:05:30Z"), "end")),
            usage.markers,
        )
        assertEquals(epoch("2026-10-12T08:00:00Z"), usage.runStartSec)
        assertEquals(epoch("2026-10-12T08:05:30Z"), usage.runEndSec)
        assertFalse(usage.cutNames)
    }

    @Test
    fun `a running Job has a start marker and no end`() {
        val usage = jobUsage(job("db-vacuum", start = "2026-10-12T08:00:00Z"))

        assertNotNull(usage)
        assertEquals(listOf(RunMarker(epoch("2026-10-12T08:00:00Z"), "start")), usage.markers)
        assertEquals(epoch("2026-10-12T08:00:00Z"), usage.runStartSec)
        assertNull(usage.runEndSec)
    }

    @Test
    fun `a Job that has not started falls back to its creation time for the run start, with no marker`() {
        val usage = jobUsage(job("db-vacuum", start = "", created = "2026-10-12T07:59:00Z"))

        assertNotNull(usage)
        assertEquals(emptyList(), usage.markers)
        assertEquals(epoch("2026-10-12T07:59:00Z"), usage.runStartSec)
        assertNull(usage.runEndSec)
    }

    @Test
    fun `a Job row without run data is read as a Job that is not Indexed and has no times`() {
        val usage = jobUsage(row("db-vacuum"))

        assertNotNull(usage)
        assertEquals(PromQl.ofJob("default", "db-vacuum", indexed = false), usage.ref)
        assertEquals(emptyList(), usage.markers)
        assertNull(usage.runStartSec)
        assertNull(usage.runEndSec)
    }

    @Test
    fun `a timestamp that cannot be read is skipped, never thrown`() {
        val usage = jobUsage(job("db-vacuum", start = "yesterday", end = "2026-13-45T99:00:00Z", created = "not a time"))

        assertNotNull(usage)
        assertEquals(emptyList(), usage.markers)
        assertNull(usage.runStartSec)
        assertNull(usage.runEndSec)
    }

    @Test
    fun `an Indexed Job is selected with an index in its pod names, and its names are cut past 50 characters`() {
        val fifty = "nightly-backup-" + "abcdefghij".repeat(4).take(35)
        val short = jobUsage(job(fifty, start = "2026-10-12T08:00:00Z", indexed = true))
        val long = jobUsage(job(fifty + "k", start = "2026-10-12T08:00:00Z", indexed = true))

        assertNotNull(short)
        assertNotNull(long)
        assertEquals("($fifty-[0-9]+-)[a-z0-9]{5}", short.ref?.pattern)
        assertFalse(short.cutNames)
        assertEquals(PromQl.ofJob("default", fifty + "k", indexed = true), long.ref)
        assertTrue(long.cutNames)
    }

    @Test
    fun `a Job that is not Indexed is cut past 57 characters`() {
        val fiftySeven = "nightly-backup-" + "abcdefghij".repeat(5).take(42)
        assertEquals(57, fiftySeven.length)

        assertFalse(jobUsage(job(fiftySeven, start = ""))!!.cutNames)
        assertTrue(jobUsage(job(fiftySeven + "k", start = ""))!!.cutNames)
    }

    @Test
    fun `a Job without a namespace has no usage, and one in an invalid namespace has no pods`() {
        assertNull(jobUsage(job("db-vacuum", start = "", namespace = null)))

        val usage = jobUsage(job("db-vacuum", start = "2026-10-12T08:00:00Z", namespace = "Team A"))
        assertNotNull(usage)
        assertNull(usage.ref)
    }

    // ── CronJob ─────────────────────────────────────────────────────────────────

    private val cronJob = row("nightly-backup", uid = cronUid)

    @Test
    fun `a CronJob marks each of its Jobs at its local start time and draws the newest Job's pods in colour`() {
        val jobs = listOf(
            job("nightly-backup-29123460", start = "2026-10-12T08:05:00Z", owners = ownedBy(cronUid)),
            job("nightly-backup-29123400", start = "2026-10-12T07:00:00Z", owners = ownedBy(cronUid)),
            job("nightly-backup-29123520", start = "2026-10-12T09:00:00Z", owners = ownedBy(cronUid)),
        )

        val utc = cronJobUsage(cronJob, jobs, ZoneOffset.UTC)
        val berlinSummer = cronJobUsage(cronJob, jobs, ZoneOffset.ofHours(2))

        assertNotNull(utc)
        assertNotNull(berlinSummer)
        assertEquals(PromQl.ofCronJob("default", "nightly-backup", jobs.map { it.name }.sorted()), utc.ref)
        assertEquals(setOf("nightly-backup-29123520-"), utc.currentPrefixes)
        assertEquals(
            listOf(
                RunMarker(epoch("2026-10-12T07:00:00Z"), "07:00"),
                RunMarker(epoch("2026-10-12T08:05:00Z"), "08:05"),
                RunMarker(epoch("2026-10-12T09:00:00Z"), "09:00"),
            ),
            utc.markers,
        )
        assertEquals(listOf("09:00", "10:05", "11:00"), berlinSummer.markers.map { it.label })
        assertEquals(epoch("2026-10-12T07:00:00Z"), utc.runStartSec)
        assertNull(utc.runEndSec)
        assertFalse(utc.cutNames)
    }

    @Test
    fun `only the Jobs the CronJob owns, in its namespace, count`() {
        val jobs = listOf(
            job("nightly-backup-29123460", start = "2026-10-12T08:05:00Z", owners = ownedBy(cronUid)),
            job("nightly-backup-29123400", start = "2026-10-12T07:00:00Z", owners = ownedBy("uid-cron-2")),
            job("nightly-backup-29123401", start = "2026-10-12T07:01:00Z", owners = ownedBy(cronUid), namespace = "production"),
            job("nightly-backup-29123402", start = "2026-10-12T07:02:00Z", owners = emptyList()),
            job("nightly-backup-29123403", start = "2026-10-12T07:03:00Z", owners = ownedBy(cronUid), namespace = null),
        )

        val usage = cronJobUsage(cronJob, jobs, ZoneOffset.UTC)

        assertNotNull(usage)
        assertEquals(listOf("08:05"), usage.markers.map { it.label })
        assertEquals(setOf("nightly-backup-29123460-"), usage.currentPrefixes)
    }

    @Test
    fun `a CronJob without a uid owns nothing`() {
        val jobs = listOf(job("nightly-backup-29123460", start = "2026-10-12T08:05:00Z", owners = ownedBy("")))

        val usage = cronJobUsage(row("nightly-backup", uid = ""), jobs, ZoneOffset.UTC)

        assertNotNull(usage)
        assertEquals(emptySet(), usage.currentPrefixes)
        assertEquals(emptyList(), usage.markers)
    }

    @Test
    fun `a CronJob with no Job still has a pattern, no current pod and no run start`() {
        val usage = cronJobUsage(cronJob, emptyList(), ZoneOffset.UTC)

        assertNotNull(usage)
        assertEquals(PromQl.ofCronJob("default", "nightly-backup", emptyList()), usage.ref)
        assertNotNull(usage.ref)
        assertEquals(emptySet(), usage.currentPrefixes)
        assertEquals(emptyList(), usage.markers)
        assertNull(usage.runStartSec)
        assertNull(usage.runEndSec)
    }

    @Test
    fun `a CronJob marks its 8 newest Jobs`() {
        val jobs = (0 until 10).map { i ->
            job("nightly-backup-${29123400 + i}", start = "2026-10-12T0$i:00:00Z", owners = ownedBy(cronUid))
        }

        val usage = cronJobUsage(cronJob, jobs, ZoneOffset.UTC)

        assertNotNull(usage)
        assertEquals((2 until 10).map { "0$it:00" }, usage.markers.map { it.label })
        assertEquals(epoch("2026-10-12T00:00:00Z"), usage.runStartSec, "the run starts at the oldest known Job, marked or not")
    }

    @Test
    fun `the run of a CronJob starts at most 7 days before its newest Job`() {
        val jobs = listOf(
            job("nightly-backup-29100000", start = "2026-09-20T08:00:00Z", owners = ownedBy(cronUid)),
            job("nightly-backup-29123460", start = "2026-10-12T08:05:00Z", owners = ownedBy(cronUid)),
        )

        val usage = cronJobUsage(cronJob, jobs, ZoneOffset.UTC)

        assertNotNull(usage)
        assertEquals(epoch("2026-10-12T08:05:00Z") - 7 * 86_400, usage.runStartSec)
    }

    @Test
    fun `a Job without a start time is ordered by its creation time, and one with neither is the oldest`() {
        val jobs = listOf(
            job("nightly-backup-29123400", start = "", created = "2026-10-12T07:00:00Z", owners = ownedBy(cronUid)),
            job("nightly-backup-29123460", start = "", created = "2026-10-12T08:05:00Z", owners = ownedBy(cronUid)),
            job("nightly-backup-29123300", start = "", created = "", owners = ownedBy(cronUid)),
        )

        val usage = cronJobUsage(cronJob, jobs, ZoneOffset.UTC)

        assertNotNull(usage)
        assertEquals(setOf("nightly-backup-29123460-"), usage.currentPrefixes)
        assertEquals(listOf("07:00", "08:05"), usage.markers.map { it.label })
    }

    @Test
    fun `two Jobs that started together are told apart by name, whatever the list order`() {
        val a = job("nightly-backup-29123400", start = "2026-10-12T07:00:00Z", owners = ownedBy(cronUid))
        val b = job("nightly-backup-29123401", start = "2026-10-12T07:00:00Z", owners = ownedBy(cronUid))

        assertEquals(cronJobUsage(cronJob, listOf(a, b), ZoneOffset.UTC), cronJobUsage(cronJob, listOf(b, a), ZoneOffset.UTC))
        assertEquals(setOf("nightly-backup-29123401-"), cronJobUsage(cronJob, listOf(a, b), ZoneOffset.UTC)?.currentPrefixes)
    }

    @Test
    fun `a CronJob of 50 characters is selected by its listed Jobs and says its names are cut`() {
        val name = "nightly-backup-" + "abcdefghij".repeat(4).take(35)
        assertEquals(50, name.length)
        val jobs = listOf(
            job("$name-29123460", start = "2026-10-12T08:05:00Z", owners = ownedBy(cronUid)),
            job("$name-29123400", start = "2026-10-12T07:00:00Z", owners = ownedBy(cronUid)),
        )

        val usage = cronJobUsage(row(name, uid = cronUid), jobs, ZoneOffset.UTC)

        assertNotNull(usage)
        assertTrue(usage.cutNames)
        assertEquals(PromQl.ofReplicaSets("default", jobs.map { it.name }), usage.ref)
        // 50 + 9 = 59 characters: the Job's pods carry the first 58 of them, without a hyphen.
        assertEquals(setOf("$name-29123460".take(58)), usage.currentPrefixes)
        assertNull(cronJobUsage(row(name, uid = cronUid), emptyList(), ZoneOffset.UTC)?.ref, "no Job is listed, so no prefix is known")
    }

    @Test
    fun `a CronJob of 48 characters is not cut`() {
        val name = "nightly-backup-" + "abcdefghij".repeat(4).take(33)
        assertEquals(48, name.length)

        assertFalse(cronJobUsage(row(name, uid = cronUid), emptyList(), ZoneOffset.UTC)!!.cutNames)
        assertTrue(cronJobUsage(row(name + "k", uid = cronUid), emptyList(), ZoneOffset.UTC)!!.cutNames)
    }

    @Test
    fun `a CronJob without a namespace has no usage`() {
        assertNull(cronJobUsage(row("nightly-backup", namespace = null), emptyList(), ZoneOffset.UTC))
    }

    // ── SparkApplication ────────────────────────────────────────────────────────

    private val driver = "spark-etl-nightly-driver"
    private val executors = listOf("spark-etl-nightly-1a2b3c4d5e6f7890-exec-1", "spark-etl-nightly-1a2b3c4d5e6f7890-exec-2")

    private fun app(
        name: String = "spark-etl-nightly",
        state: String = "COMPLETED",
        submitted: String = "2026-10-12T08:00:00Z",
        terminated: String = "2026-10-12T08:20:00Z",
        driverPod: String = driver,
        executorPods: List<String> = executors,
        created: String = "",
        namespace: String? = "production",
        labels: Map<String, String> = emptyMap(),
        owners: List<OwnerRefInfo> = emptyList(),
    ) = row(
        name = name,
        namespace = namespace,
        created = created,
        labels = labels,
        owners = owners,
        spark = SparkRun(driverPod = driverPod, executorPods = executorPods, submittedAt = submitted, terminatedAt = terminated, state = state),
    )

    @Test
    fun `a completed SparkApplication selects its driver and executors and marks where it was submitted and ended`() {
        val usage = sparkUsage(app())

        assertNotNull(usage)
        assertEquals(PromQl.ofExactPods("production", listOf(driver) + executors), usage.ref)
        assertNull(usage.currentPrefixes)
        assertEquals(
            listOf(RunMarker(epoch("2026-10-12T08:00:00Z"), "submitted"), RunMarker(epoch("2026-10-12T08:20:00Z"), "ended")),
            usage.markers,
        )
        assertEquals(epoch("2026-10-12T08:00:00Z"), usage.runStartSec)
        assertEquals(epoch("2026-10-12T08:20:00Z"), usage.runEndSec)
        assertFalse(usage.cutNames)
    }

    @Test
    fun `a failed SparkApplication ends the run too`() {
        val usage = sparkUsage(app(state = "FAILED"))

        assertEquals(epoch("2026-10-12T08:20:00Z"), usage?.runEndSec)
        assertEquals(listOf("submitted", "ended"), usage?.markers?.map { it.label })
    }

    @Test
    fun `a running SparkApplication has no end, even when an earlier attempt left a termination time`() {
        val usage = sparkUsage(app(state = "RUNNING", terminated = "2026-10-12T07:30:00Z"))

        assertNotNull(usage)
        assertNull(usage.runEndSec)
        assertEquals(listOf("submitted"), usage.markers.map { it.label })
        assertEquals(epoch("2026-10-12T08:00:00Z"), usage.runStartSec)
    }

    @Test
    fun `a SparkApplication that was never submitted starts at its creation time`() {
        val usage = sparkUsage(app(state = "", submitted = "", terminated = "", executorPods = emptyList(), created = "2026-10-12T07:55:00Z"))

        assertNotNull(usage)
        assertEquals("(spark-etl-nightly-driver)", usage.ref?.pattern)
        assertEquals(emptyList(), usage.markers)
        assertEquals(epoch("2026-10-12T07:55:00Z"), usage.runStartSec)
        assertNull(usage.runEndSec)
    }

    @Test
    fun `a SparkApplication row without Spark data has no pods`() {
        val usage = sparkUsage(row("spark-etl-nightly", namespace = "production", created = "2026-10-12T07:55:00Z"))

        assertNotNull(usage)
        assertNull(usage.ref)
        assertNull(usage.currentPrefixes)
        assertEquals(emptyList(), usage.markers)
        assertEquals(epoch("2026-10-12T07:55:00Z"), usage.runStartSec)
        assertNull(usage.runEndSec)
    }

    @Test
    fun `a SparkApplication with many executors is selected by stem`() {
        val many = (1..80).map { "spark-etl-nightly-1a2b3c4d5e6f7890-exec-$it" }

        val usage = sparkUsage(app(executorPods = many))

        assertEquals("(spark-etl-nightly-driver|spark-etl-nightly-1a2b3c4d5e6f7890-exec-[0-9]+)", usage?.ref?.pattern)
    }

    @Test
    fun `a SparkApplication without a namespace has no usage`() {
        assertNull(sparkUsage(app(namespace = null)))
    }

    // ── ScheduledSparkApplication ───────────────────────────────────────────────

    private val scheduledUid = "uid-sched-1"
    private val scheduled = row("spark-report-hourly", namespace = "production", uid = scheduledUid)
    private val scheduledLabel = "sparkoperator.k8s.io/scheduled-app-name"

    private fun scheduledRun(suffix: String, submitted: String, ownerUid: String? = scheduledUid, label: String? = null, namespace: String? = "production", created: String = "") = app(
        name = "spark-report-hourly-$suffix",
        submitted = submitted,
        terminated = "",
        state = "COMPLETED",
        driverPod = "spark-report-hourly-$suffix-driver",
        executorPods = listOf("spark-report-hourly-$suffix-1a2b3c4d5e6f7890-exec-1"),
        namespace = namespace,
        created = created,
        labels = if (label != null) mapOf(scheduledLabel to label) else emptyMap(),
        owners = if (ownerUid != null) ownedBy(ownerUid, kind = "ScheduledSparkApplication") else emptyList(),
    )

    @Test
    fun `a ScheduledSparkApplication selects the pods of every run it owns and marks each run's submission`() {
        val runs = listOf(
            scheduledRun("1760256000", submitted = "2026-10-12T08:00:00Z"),
            scheduledRun("1760252400", submitted = "2026-10-12T07:00:00Z"),
        )

        val usage = scheduledSparkUsage(scheduled, runs, ZoneOffset.UTC)

        assertNotNull(usage)
        assertEquals(
            PromQl.ofExactPods(
                "production",
                listOf(
                    "spark-report-hourly-1760252400-driver",
                    "spark-report-hourly-1760252400-1a2b3c4d5e6f7890-exec-1",
                    "spark-report-hourly-1760256000-driver",
                    "spark-report-hourly-1760256000-1a2b3c4d5e6f7890-exec-1",
                ),
            ),
            usage.ref,
        )
        assertNull(usage.currentPrefixes)
        assertEquals(
            listOf(RunMarker(epoch("2026-10-12T07:00:00Z"), "07:00"), RunMarker(epoch("2026-10-12T08:00:00Z"), "08:00")),
            usage.markers,
        )
        assertEquals(epoch("2026-10-12T07:00:00Z"), usage.runStartSec)
        assertNull(usage.runEndSec)
        assertFalse(usage.cutNames)
        assertEquals(listOf("09:00", "10:00"), scheduledSparkUsage(scheduled, runs, ZoneOffset.ofHours(2))?.markers?.map { it.label })
    }

    @Test
    fun `a run counts by owner uid, else by the operator's label, and only in the scheduled application's namespace`() {
        val runs = listOf(
            scheduledRun("1760256000", submitted = "2026-10-12T08:00:00Z"),
            scheduledRun("1760252400", submitted = "2026-10-12T07:00:00Z", ownerUid = null, label = "spark-report-hourly"),
            scheduledRun("1760248800", submitted = "2026-10-12T06:00:00Z", ownerUid = "uid-sched-2"),
            scheduledRun("1760245200", submitted = "2026-10-12T05:00:00Z", ownerUid = null, label = "spark-report-daily"),
            scheduledRun("1760241600", submitted = "2026-10-12T04:00:00Z", namespace = "default"),
            scheduledRun("1760238000", submitted = "2026-10-12T03:00:00Z", namespace = null),
            scheduledRun("1760234400", submitted = "2026-10-12T02:00:00Z", ownerUid = null),
        )

        val usage = scheduledSparkUsage(scheduled, runs, ZoneOffset.UTC)

        assertNotNull(usage)
        assertEquals(listOf("07:00", "08:00"), usage.markers.map { it.label })
    }

    @Test
    fun `a run that was never submitted is marked at its creation time`() {
        val usage = scheduledSparkUsage(
            scheduled,
            listOf(scheduledRun("1760256000", submitted = "", created = "2026-10-12T08:00:30Z")),
            ZoneOffset.UTC,
        )

        assertNotNull(usage)
        assertEquals(listOf("08:00"), usage.markers.map { it.label })
        assertEquals(epoch("2026-10-12T08:00:30Z"), usage.runStartSec)
    }

    @Test
    fun `a ScheduledSparkApplication marks its 8 newest runs and starts its run at most 7 days before the newest`() {
        val runs = (0 until 10).map { i -> scheduledRun("17602560$i", submitted = "2026-10-12T0$i:00:00Z") } +
            scheduledRun("1750000000", submitted = "2026-09-01T08:00:00Z")

        val usage = scheduledSparkUsage(scheduled, runs, ZoneOffset.UTC)

        assertNotNull(usage)
        assertEquals((2 until 10).map { "0$it:00" }, usage.markers.map { it.label })
        assertEquals(epoch("2026-10-12T09:00:00Z") - 7 * 86_400, usage.runStartSec)
    }

    @Test
    fun `a ScheduledSparkApplication with no run has no pods`() {
        val usage = scheduledSparkUsage(scheduled, emptyList(), ZoneOffset.UTC)

        assertNotNull(usage)
        assertNull(usage.ref)
        assertEquals(emptyList(), usage.markers)
        assertNull(usage.runStartSec)
        assertNull(usage.runEndSec)
    }

    @Test
    fun `a run row without Spark data adds no pod, and a ScheduledSparkApplication without a uid matches by label only`() {
        val withoutSpark = row("spark-report-hourly-1760256000", namespace = "production", owners = ownedBy(scheduledUid), created = "2026-10-12T08:00:00Z")

        val usage = scheduledSparkUsage(scheduled, listOf(withoutSpark), ZoneOffset.UTC)
        assertNotNull(usage)
        assertNull(usage.ref)
        assertEquals(listOf("08:00"), usage.markers.map { it.label })

        val noUid = scheduledSparkUsage(row("spark-report-hourly", namespace = "production", uid = ""), listOf(scheduledRun("1760256000", submitted = "2026-10-12T08:00:00Z", ownerUid = "")), ZoneOffset.UTC)
        assertNotNull(noUid)
        assertEquals(emptyList(), noUid.markers)
    }

    @Test
    fun `a ScheduledSparkApplication without a namespace has no usage`() {
        assertNull(scheduledSparkUsage(row("spark-report-hourly", namespace = null), emptyList(), ZoneOffset.UTC))
    }
}
