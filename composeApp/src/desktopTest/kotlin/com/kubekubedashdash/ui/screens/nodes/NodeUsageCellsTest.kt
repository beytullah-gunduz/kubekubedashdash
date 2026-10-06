package com.kubekubedashdash.ui.screens.nodes

import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.formatUsagePair
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NodeUsageCellsTest {
    private companion object {
        const val GIB = 1024L * 1024 * 1024
    }

    private fun node(cpu: String = "4", memory: String = "16000000Ki") = NodeInfo(
        uid = "u1",
        name = "node-a",
        status = "Ready",
        roles = "<none>",
        version = "v1.30.2",
        os = "",
        arch = "amd64",
        containerRuntime = "",
        cpu = cpu,
        memory = memory,
        pods = "110",
        age = "1d",
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    @Test
    fun `with a sample the cell reads percent, then used over allocatable`() {
        val usage = NodeResourceUsage("node-a", 1200, 4000, 11 * GIB, 16 * GIB)

        val cpu = nodeCpuModel(node(), usage)
        assertEquals(usage.cpuFraction, cpu.fraction)
        assertEquals(
            "${(usage.cpuFraction * 100).toInt()}% · " + formatUsagePair(formatCpuCores(1200), formatCpuCores(4000)),
            cpu.text,
        )

        val memory = nodeMemoryModel(node(), usage)
        assertEquals(usage.memoryFraction, memory.fraction)
        assertEquals(
            "${(usage.memoryFraction * 100).toInt()}% · " + formatUsagePair(formatMemorySize(11 * GIB), formatMemorySize(16 * GIB)),
            memory.text,
        )
    }

    @Test
    fun `without a sample the cell shows readable allocatable and no fraction`() {
        val memory = nodeMemoryModel(node(), null)
        assertEquals(formatMemorySize(16000000L * 1024), memory.text)
        assertNull(memory.fraction)

        val cpu = nodeCpuModel(node(), null)
        assertEquals(formatCpuCores(4000), cpu.text)
        assertNull(cpu.fraction)
    }

    @Test
    fun `a zero capacity falls back to allocatable`() {
        val usage = NodeResourceUsage("node-a", 1200, 0, 0, 0)

        assertNull(nodeCpuModel(node(), usage).fraction)
        assertNull(nodeMemoryModel(node(), usage).fraction)
        assertEquals(formatCpuCores(4000), nodeCpuModel(node(), usage).text)
    }

    @Test
    fun `a blank allocatable shows the placeholder`() {
        assertEquals(NONE_PLACEHOLDER, nodeCpuModel(node(cpu = ""), null).text)
        assertEquals(NONE_PLACEHOLDER, nodeMemoryModel(node(memory = ""), null).text)
    }

    @Test
    fun `the cell sorts by fraction, a fallback cell has no number`() {
        val usage = NodeResourceUsage("node-a", 1200, 4000, 11 * GIB, 16 * GIB)
        val model = nodeCpuModel(node(), usage)
        val cell = nodeUsageCell(model)

        assertEquals(model.fraction!!.toDouble(), cell.sortNumber)
        assertNotNull(cell.content)

        val fallback = nodeUsageCell(nodeCpuModel(node(), null))
        assertNull(fallback.sortNumber)
        assertNull(fallback.content)
    }
}
