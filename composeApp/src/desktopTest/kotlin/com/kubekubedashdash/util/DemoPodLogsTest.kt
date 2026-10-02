package com.kubekubedashdash.util

import com.kubekubedashdash.services.LogStreamOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DemoPodLogsTest {

    private val now = Instant.parse("2026-10-01T12:00:00Z")

    @Test
    fun `history returns exactly count lines and is deterministic`() {
        val a = DemoPodLogs.history("production", "frontend-abc", null, 60, timestamps = false, now = now)
        val again = DemoPodLogs.history("production", "frontend-abc", null, 60, timestamps = false, now = now)
        val other = DemoPodLogs.history("production", "frontend-xyz", null, 60, timestamps = false, now = now)

        assertEquals(60, a.size)
        assertEquals(a, again)
        assertNotEquals(a, other)
    }

    @Test
    fun `history with a non-positive count is empty`() {
        assertEquals(emptyList(), DemoPodLogs.history("production", "frontend-abc", null, 0, timestamps = false, now = now))
        assertEquals(emptyList(), DemoPodLogs.history("production", "frontend-abc", null, -5, timestamps = false, now = now))
    }

    @Test
    fun `nginx family writes access-log lines`() {
        val lines = DemoPodLogs.history("production", "frontend-x", null, 50, timestamps = false, now = now)

        val access = Regex("""^10\.244\.\d+\.\d+ - - \[""")
        assertTrue(lines.all { access.containsMatchIn(it) }, lines.firstOrNull { !access.containsMatchIn(it) })
        // The month is spelled out in English whatever the machine's locale.
        assertTrue(lines.all { "/Oct/2026:" in it }, lines.first())
    }

    @Test
    fun `api family writes one JSON object per line with a level`() {
        val lines = DemoPodLogs.history("production", "api-x", null, 200, timestamps = false, now = now)

        lines.forEach { line ->
            val obj: JsonObject = Json.parseToJsonElement(line).jsonObject
            assertTrue("level" in obj, line)
            assertTrue("ts" in obj, line)
        }
    }

    @Test
    fun `redis family writes redis-style lines`() {
        val lines = DemoPodLogs.history("production", "redis-x", null, 50, timestamps = false, now = now)

        assertTrue(lines.all { it.startsWith("1:M ") }, lines.firstOrNull { !it.startsWith("1:M ") })
    }

    @Test
    fun `an unknown family falls back to the generic application format`() {
        val lines = DemoPodLogs.history("production", "worker-x", null, 50, timestamps = false, now = now)

        val generic = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z (INFO|WARN|ERROR) \[worker-\d+] """)
        assertTrue(lines.all { generic.containsMatchIn(it) }, lines.firstOrNull { !generic.containsMatchIn(it) })
    }

    @Test
    fun `timestamps prefix every line with nine fraction digits like kubectl`() {
        val lines = DemoPodLogs.history("production", "worker-x", null, 40, timestamps = true, now = now)

        val prefix = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{9}Z """)
        assertTrue(lines.all { prefix.containsMatchIn(it) }, lines.firstOrNull { !prefix.containsMatchIn(it) })
    }

    @Test
    fun `stream emits the tail at once then keeps trickling live lines without completing`() = runTest {
        val lines = mutableListOf<String>()
        val job = backgroundScope.launch {
            DemoPodLogs.stream(
                namespace = "production",
                pod = "frontend-abc",
                container = null,
                options = LogStreamOptions(),
                minGapMs = 400,
                maxGapMs = 1_000,
                clock = { now },
            ).collect { lines += it }
        }

        runCurrent()
        assertEquals(100, lines.size)

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(lines.size >= 104, "expected at least 4 live lines, got ${lines.size - 100}")
        assertTrue(job.isActive)
    }

    @Test
    fun `stream drops history older than sinceSeconds`() = runTest {
        val lines = mutableListOf<String>()
        backgroundScope.launch {
            DemoPodLogs.stream(
                namespace = "production",
                pod = "frontend-abc",
                container = null,
                options = LogStreamOptions(sinceSeconds = 30),
                clock = { now },
            ).collect { lines += it }
        }

        runCurrent()
        // 100 lines, 0.8-4 s apart: the last 30 s hold between 8 and 38 of them.
        assertTrue(lines.size in 1..99, "got ${lines.size} lines")
    }

    @Test
    fun `stream of a previous container completes with a crash ending`() = runTest {
        val lines = DemoPodLogs.stream(
            namespace = "production",
            pod = "api-abc",
            container = null,
            options = LogStreamOptions(previous = true),
            clock = { now },
        ).toList()

        assertTrue(lines.isNotEmpty())
        assertTrue("exit status" in lines.last(), lines.last())
    }

    @Test
    fun `terminated returns count lines ending in an exit status`() {
        listOf("frontend-a", "api-a", "redis-a", "kafka-0", "spark-driver-a", "worker-a").forEach { pod ->
            val lines = DemoPodLogs.terminated("production", pod, null, 40, timestamps = false, now = now)

            assertEquals(40, lines.size, pod)
            assertTrue("exit status" in lines.last(), "$pod: ${lines.last()}")
            assertTrue(lines.takeLast(3).first().let { "OutOfMemoryError" in it || "[emerg]" in it }, "$pod: ${lines.takeLast(3)}")
        }
    }
}
