package com.kubekubedashdash.ui.screens.workloads

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
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

/** The pure builders behind the usage sections of the Job and CronJob panels. */
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
    ) = GenericResourceInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        status = null,
        age = "1m",
        labels = emptyMap(),
        annotations = emptyMap(),
        owners = owners,
        creationTimestamp = created,
        run = run,
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
}
