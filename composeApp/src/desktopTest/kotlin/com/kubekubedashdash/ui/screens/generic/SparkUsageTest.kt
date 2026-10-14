package com.kubekubedashdash.ui.screens.generic

import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodUsage
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.models.podUsageKey
import com.kubekubedashdash.ui.components.KpiTone
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkUsageTest {

    private val gib = 1L shl 30

    private fun app(name: String, uid: String = "uid-$name", namespace: String = "production") = GenericResourceInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        status = null,
        age = "1h",
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    private fun pod(
        name: String,
        namespace: String = "production",
        appName: String? = null,
        phase: String = "Running",
        owners: List<OwnerRefInfo> = emptyList(),
        resources: List<ContainerResources> = listOf(ContainerResources("main", cpuRequestMillis = 1000, memoryRequestBytes = gib)),
    ) = PodInfo(
        uid = "uid-$name",
        name = name,
        namespace = namespace,
        status = phase,
        ready = "1/1",
        restarts = 0,
        age = "1h",
        node = "node-1",
        ip = "10.0.0.1",
        labels = if (appName == null) emptyMap() else mapOf(SPARK_APP_NAME_LABEL to appName),
        annotations = emptyMap(),
        containers = emptyList(),
        phase = phase,
        owners = owners,
        resources = resources,
    )

    private fun owner(uid: String) = OwnerRefInfo(kind = "Pod", name = uid, uid = uid, controller = true)

    private fun sample(pod: PodInfo, cpu: Long, memory: Long) = podUsageKey(pod.namespace, pod.name) to PodUsage(cpu, memory, emptyList())

    @Test
    fun `label attribution sums a driver and its executors`() {
        val pi = app("spark-pi")
        val pods = listOf(pod("pi-driver", appName = "spark-pi"), pod("pi-exec-1", appName = "spark-pi"), pod("pi-exec-2", appName = "spark-pi"))
        val usages = pods.mapIndexed { i, p -> sample(p, 100L * (i + 1), gib * (i + 1)) }.toMap()

        val result = sparkAppUsages(listOf(pi), pods, usages).getValue(sparkUsageKey(pi))

        assertEquals(SparkAppUsage(livePods = 3, cpuUsedMillis = 600, memoryUsedBytes = 6 * gib, cpuRequestMillis = 3000, memoryRequestBytes = 3 * gib), result)
    }

    @Test
    fun `owner references attribute a driver by the app uid and executors by the driver uid`() {
        val pi = app("spark-pi")
        val driver = pod("pi-driver", owners = listOf(owner("uid-spark-pi")))
        val executor = pod("pi-exec-1", owners = listOf(owner(driver.uid)))
        val stranger = pod("other-pod", owners = listOf(owner("uid-unrelated")))

        val result = sparkAppUsages(listOf(pi), listOf(executor, stranger, driver), emptyMap()).getValue(sparkUsageKey(pi))

        assertEquals(2, result.livePods)
        assertEquals(2000, result.cpuRequestMillis)
    }

    @Test
    fun `a pod of another namespace is not attributed`() {
        val pi = app("spark-pi")
        val elsewhere = pod("pi-driver", namespace = "other-ns", appName = "spark-pi")

        val result = sparkAppUsages(listOf(pi), listOf(elsewhere), mapOf(sample(elsewhere, 500, gib))).getValue(sparkUsageKey(pi))

        assertEquals(SparkAppUsage(0, null, null, 0, 0), result)
    }

    @Test
    fun `terminated pods count for nothing`() {
        val pi = app("spark-pi")
        val live = pod("pi-driver", appName = "spark-pi")
        val done = pod("pi-exec-1", appName = "spark-pi", phase = "Succeeded")
        val failed = pod("pi-exec-2", appName = "spark-pi", phase = "Failed")
        val usages = listOf(live, done, failed).associate { sample(it, 100, gib) }

        val result = sparkAppUsages(listOf(pi), listOf(live, done, failed), usages).getValue(sparkUsageKey(pi))

        assertEquals(SparkAppUsage(livePods = 1, cpuUsedMillis = 100, memoryUsedBytes = gib, cpuRequestMillis = 1000, memoryRequestBytes = gib), result)
    }

    @Test
    fun `without a sample the usage is null and the cells show the placeholder`() {
        val pi = app("spark-pi")
        val idle = app("spark-idle")
        val driver = pod("pi-driver", appName = "spark-pi")

        val result = sparkAppUsages(listOf(pi, idle), listOf(driver), emptyMap())

        val withPod = result.getValue(sparkUsageKey(pi))
        assertEquals(1, withPod.livePods)
        assertNull(withPod.cpuUsedMillis)
        assertNull(withPod.memoryUsedBytes)
        assertEquals(1000, withPod.cpuRequestMillis)
        assertEquals(SparkAppUsage(0, null, null, 0, 0), result.getValue(sparkUsageKey(idle)))
        for (cell in listOf(sparkCpuCell(withPod), sparkMemoryCell(withPod), sparkCpuCell(null), sparkMemoryCell(null))) {
            assertEquals(NONE_PLACEHOLDER, cell.text)
            assertNull(cell.sortNumber)
        }
    }

    @Test
    fun `a container without a request adds nothing`() {
        val pi = app("spark-pi")
        val driver = pod(
            "pi-driver",
            appName = "spark-pi",
            resources = listOf(ContainerResources("main", cpuRequestMillis = 500), ContainerResources("sidecar"), ContainerResources("mem", memoryRequestBytes = gib)),
        )

        val result = sparkAppUsages(listOf(pi), listOf(driver), emptyMap()).getValue(sparkUsageKey(pi))

        assertEquals(500, result.cpuRequestMillis)
        assertEquals(gib, result.memoryRequestBytes)
    }

    @Test
    fun `an application with a blank uid is keyed by namespace and name`() {
        val pi = app("spark-pi", uid = "")
        val driver = pod("pi-driver", appName = "spark-pi")

        val result = sparkAppUsages(listOf(pi), listOf(driver), emptyMap())

        assertEquals("production/spark-pi", sparkUsageKey(pi))
        assertEquals(1, result.getValue("production/spark-pi").livePods)
    }

    @Test
    fun `cells show the summed usage and sort by number`() {
        val usage = SparkAppUsage(livePods = 3, cpuUsedMillis = 1500, memoryUsedBytes = 3 * gib, cpuRequestMillis = 2000, memoryRequestBytes = 4 * gib)
        assertEquals(formatCpuCores(1500), sparkCpuCell(usage).text)
        assertEquals(1500.0, sparkCpuCell(usage).sortNumber)
        assertEquals(formatMemorySize(3 * gib), sparkMemoryCell(usage).text)
        assertEquals((3 * gib).toDouble(), sparkMemoryCell(usage).sortNumber)
        assertTrue(sparkCpuCell(usage).content != null)
    }

    private fun summary(metrics: Boolean = true, cpuCapacity: Long = 4000, memoryCapacity: Long = 16 * gib) = ResourceUsageSummary(
        cpuUsedMillis = 0,
        cpuCapacityMillis = cpuCapacity,
        memoryUsedBytes = 0,
        memoryCapacityBytes = memoryCapacity,
        metricsAvailable = metrics,
    )

    private val oneApp = listOf(SparkAppUsage(livePods = 3, cpuUsedMillis = 1500, memoryUsedBytes = 2 * gib, cpuRequestMillis = 1000, memoryRequestBytes = 4 * gib))

    @Test
    fun `the strip has used and requested chips with the share of the cluster`() {
        val kpis = sparkKpis(oneApp, summary())

        assertEquals(listOf("cpu-used", "cpu-req", "mem-used", "mem-req"), kpis.map { it.id })
        assertEquals("CPU ${formatCpuCores(1500)} used", kpis[0].label)
        assertEquals(KpiTone.Neutral, kpis[0].tone)
        assertEquals("CPU ${formatCpuCores(1000)} requested · 25 % of cluster allocatable", kpis[1].label)
        assertEquals("Mem ${formatMemorySize(2 * gib)} used", kpis[2].label)
        assertEquals("Mem ${formatMemorySize(4 * gib)} requested · 25 % of cluster allocatable", kpis[3].label)
    }

    @Test
    fun `the share is omitted when the allocatable is unknown`() {
        val kpis = sparkKpis(oneApp, summary(cpuCapacity = 0, memoryCapacity = 0))

        assertEquals("CPU ${formatCpuCores(1000)} requested", kpis[1].label)
        assertEquals("Mem ${formatMemorySize(4 * gib)} requested", kpis[3].label)
        assertFalse(kpis.any { it.label.contains("allocatable") })
    }

    @Test
    fun `the used chips are muted dashes without metrics or a summary`() {
        for (summary in listOf(summary(metrics = false), null)) {
            val kpis = sparkKpis(oneApp, summary)
            assertEquals("CPU used —", kpis[0].label)
            assertEquals(KpiTone.Muted, kpis[0].tone)
            assertEquals("Mem used —", kpis[2].label)
            assertEquals(KpiTone.Muted, kpis[2].tone)
            assertEquals(KpiTone.Neutral, kpis[1].tone)
        }
        assertEquals("CPU ${formatCpuCores(1000)} requested", sparkKpis(oneApp, null)[1].label)
    }

    @Test
    fun `only the SparkApplication kind of the operator group qualifies`() {
        assertTrue(isSparkApplicationKind("SparkApplication", "sparkoperator.k8s.io"))
        assertFalse(isSparkApplicationKind("ScheduledSparkApplication", "sparkoperator.k8s.io"))
        assertFalse(isSparkApplicationKind("SparkApplication", "example.com"))
        assertFalse(isSparkApplicationKind("SparkApplication", null))
    }
}
