package com.kubekubedashdash.ui.screens.pods

import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.ContainerUsage
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodUsage
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.UsageLevel
import com.kubekubedashdash.util.formatMemorySize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Covers the Pods table's CPU / Memory cell logic: the per-container memory alert, the tooltips and the empty cells. */
class PodUsageCellsTest {

    private companion object {
        const val MIB = 1024L * 1024
    }

    private fun usage(vararg c: ContainerUsage) = PodUsage(c.sumOf { it.cpuMillis }, c.sumOf { it.memoryBytes }, c.toList())

    private fun pod(resources: List<ContainerResources>) = PodInfo(
        uid = "pod-a",
        name = "web-0",
        namespace = "default",
        status = "Running",
        ready = "1/1",
        restarts = 0,
        age = "1h",
        node = "node-a",
        ip = "10.0.0.1",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = listOf(
            ContainerInfo(name = "app", image = "fake.example/app:latest", ready = true, restartCount = 0, state = "Running"),
        ),
        phase = "Running",
        resources = resources,
    )

    private fun resources(name: String, memoryLimit: Long? = null, memoryRequest: Long? = null) = ContainerResources(name = name, memoryRequestBytes = memoryRequest, memoryLimitBytes = memoryLimit)

    private fun app(memory: Long) = ContainerUsage("app", cpuMillis = 100, memoryBytes = memory)

    @Test
    fun `the alert follows the container closest to its own limit, not the pod total`() {
        val usage = usage(app(500 * MIB), ContainerUsage("sidecar", cpuMillis = 10, memoryBytes = 10 * MIB))
        val resources = listOf(resources("app", memoryLimit = 512 * MIB), resources("sidecar", memoryLimit = 512 * MIB))

        val worst = worstMemoryPressure(usage, resources)
        assertNotNull(worst)
        assertEquals("app", worst.container)
        assertEquals(UsageLevel.CRITICAL, memoryLevel(usage, resources))
    }

    @Test
    fun `no memory limit means no alert`() {
        val usage = usage(app(500 * MIB))
        val resources = listOf(resources("app", memoryLimit = null))

        assertNull(worstMemoryPressure(usage, resources))
        assertEquals(UsageLevel.NORMAL, memoryLevel(usage, resources))
    }

    @Test
    fun `a container without a limit or without a sample is skipped`() {
        val usage = usage(app(100 * MIB), ContainerUsage("sidecar", cpuMillis = 10, memoryBytes = 60 * MIB))
        val resources = listOf(
            resources("app", memoryLimit = 512 * MIB),
            resources("other", memoryLimit = 64 * MIB), // limit but no sample
            resources("sidecar"), // sample but no limit
        )

        val worst = worstMemoryPressure(usage, resources)
        assertNotNull(worst)
        assertEquals("app", worst.container)
        assertEquals(100 * MIB, worst.usedBytes)
        assertEquals(512 * MIB, worst.limitBytes)
    }

    @Test
    fun `levels at the edges`() {
        val resources = listOf(resources("app", memoryLimit = 512 * MIB))

        assertEquals(UsageLevel.WARNING, memoryLevel(usage(app(410 * MIB)), resources))
        assertEquals(UsageLevel.CRITICAL, memoryLevel(usage(app(461 * MIB)), resources))
        assertEquals(UsageLevel.NORMAL, memoryLevel(usage(app(409 * MIB)), resources))
    }

    @Test
    fun `resourceLine sums, says none, or counts`() {
        assertEquals("Limit: ${formatMemorySize(768 * MIB)}", resourceLine("Limit", listOf(512 * MIB, 256 * MIB), ::formatMemorySize))
        assertEquals("Limit: none", resourceLine("Limit", listOf(null, null), ::formatMemorySize))
        assertEquals("Limit: none", resourceLine("Limit", emptyList(), ::formatMemorySize))
        assertEquals("Limit: set on 1 of 2 containers", resourceLine("Limit", listOf(512 * MIB, null), ::formatMemorySize))
    }

    @Test
    fun `memory tooltip`() {
        val single = memoryTooltip(
            usage(app(480 * MIB)),
            listOf(resources("app", memoryLimit = 512 * MIB, memoryRequest = 256 * MIB)),
        )
        assertEquals("Used: 480 MiB\nRequest: 256 MiB\nLimit: 512 MiB\n93% of the limit", single)

        val two = memoryTooltip(
            usage(app(500 * MIB), ContainerUsage("sidecar", cpuMillis = 10, memoryBytes = 10 * MIB)),
            listOf(resources("app", memoryLimit = 512 * MIB), resources("sidecar", memoryLimit = 512 * MIB)),
        )
        assertEquals("Closest to its limit: app (97% of 512 MiB)", two.lines().last())
    }

    @Test
    fun `cpu tooltip`() {
        val usage = usage(ContainerUsage("app", cpuMillis = 125, memoryBytes = 64 * MIB))
        val resources = listOf(ContainerResources(name = "app", cpuRequestMillis = 100, cpuLimitMillis = 500))

        assertEquals("Used: 125m\nRequest: 100m\nLimit: 500m", cpuTooltip(usage, resources))
    }

    @Test
    fun `cells show a placeholder without a sample and carry the number with one`() {
        val pod = pod(listOf(resources("app", memoryLimit = 512 * MIB)))

        for (cell in listOf(cpuCell(pod, null), memoryCell(pod, null))) {
            assertEquals(NONE_PLACEHOLDER, cell.text)
            assertNull(cell.sortNumber)
            assertNull(cell.content)
        }

        val usage = usage(app(480 * MIB))
        val memory = memoryCell(pod, usage)
        assertEquals(usage.memoryBytes.toDouble(), memory.sortNumber)
        assertEquals(formatMemorySize(usage.memoryBytes), memory.text)
        assertNotNull(memory.content)
    }
}
