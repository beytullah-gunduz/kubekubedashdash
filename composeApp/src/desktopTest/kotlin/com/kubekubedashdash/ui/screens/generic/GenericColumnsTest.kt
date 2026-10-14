package com.kubekubedashdash.ui.screens.generic

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.SparkRun
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenericColumnsTest {

    private val sparkPrinterColumns = linkedMapOf(
        "Suspend" to "<none>",
        "Status" to "COMPLETED",
        "Attempts" to "1",
        "Start" to "2026-01-01T10:00:00Z",
        "Finish" to "2026-01-01T10:12:30Z",
        "Age" to "2h",
    )

    private fun app(name: String, state: String, terminatedAt: String, extra: Map<String, String> = sparkPrinterColumns) = GenericResourceInfo(
        uid = "uid-$name",
        name = name,
        namespace = "production",
        status = null,
        age = "2h",
        labels = emptyMap(),
        annotations = emptyMap(),
        extraColumns = extra,
        spark = SparkRun("$name-driver", emptyList(), "2026-01-01T10:00:00Z", terminatedAt, state),
    )

    private fun List<GenericColumn>.headers() = map { it.header }

    private fun List<GenericColumn>.cell(header: String, row: GenericResourceInfo) = single { it.header == header }.cell(row)

    @Test
    fun `a SparkApplication table has Duration just before Age`() {
        val columns = genericColumns(listOf(app("done", "COMPLETED", "2026-01-01T10:12:30Z")), namespacedKind = true)
        assertEquals(listOf("Name", "Namespace", "Suspend", "Status", "Attempts", "Start", "Finish", "Duration", "Age"), columns.headers())
        assertEquals(0f, columns.single { it.header == "Duration" }.minTableWidth.value)
    }

    @Test
    fun `Duration shows a finished run and sorts it by seconds`() {
        val done = app("done", "COMPLETED", "2026-01-01T10:12:30Z")
        val failed = app("failed", "FAILED", "2026-01-01T11:05:00Z")
        val columns = genericColumns(listOf(done, failed), namespacedKind = true)
        assertEquals("12m", columns.cell("Duration", done).text)
        assertEquals(750.0, columns.cell("Duration", done).sortNumber)
        assertEquals("1h5m", columns.cell("Duration", failed).text)
        assertEquals(3_900.0, columns.cell("Duration", failed).sortNumber)
    }

    @Test
    fun `Duration is blank while the application runs`() {
        val running = app("running", "RUNNING", "2026-01-01T09:00:00Z")
        val cell = genericColumns(listOf(running), namespacedKind = true).cell("Duration", running)
        assertEquals("", cell.text)
        assertNull(cell.sortNumber)
    }

    @Test
    fun `a printer Duration column gives way to the built-in one on SparkApplications only`() {
        val withPrinterDuration = sparkPrinterColumns + ("Duration" to "printer")
        val sparkColumns = genericColumns(listOf(app("done", "COMPLETED", "2026-01-01T10:12:30Z", withPrinterDuration)), namespacedKind = true)
        assertEquals(1, sparkColumns.count { it.header == "Duration" })
        assertEquals("12m", sparkColumns.cell("Duration", app("done", "COMPLETED", "2026-01-01T10:12:30Z", withPrinterDuration)).text)

        val other = app("other", "COMPLETED", "", mapOf("Duration" to "printer")).copy(spark = null)
        val otherColumns = genericColumns(listOf(other), namespacedKind = true)
        assertEquals(listOf("Name", "Namespace", "Duration", "Age"), otherColumns.headers())
        assertEquals("printer", otherColumns.cell("Duration", other).text)
    }

    @Test
    fun `other kinds get no Duration column`() {
        val job = app("job", "COMPLETED", "", mapOf("Completions" to "1/1")).copy(spark = null, status = "Complete")
        assertEquals(listOf("Name", "Status", "Completions", "Age"), genericColumns(listOf(job), namespacedKind = false).headers())
    }

    @Test
    fun `a timestamp of today shows its time only`() {
        val now = Instant.parse("2026-01-01T23:30:00Z")
        assertEquals("10:00:00Z", shortenTodayTimestamp("2026-01-01T10:00:00Z", now))
        assertEquals("10:00:00.123456Z", shortenTodayTimestamp("2026-01-01T10:00:00.123456Z", now))
        assertEquals("2025-12-31T10:00:00Z", shortenTodayTimestamp("2025-12-31T10:00:00Z", now))
        assertEquals("2026-01-02T00:10:00Z", shortenTodayTimestamp("2026-01-02T00:10:00Z", now))
    }

    @Test
    fun `values that are not a whole UTC timestamp stay as they are`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        for (value in listOf("", "<none>", "COMPLETED", "2026-01-01", "2026-01-01T10:00:00+02:00", "started 2026-01-01T10:00:00Z")) {
            assertEquals(value, shortenTodayTimestamp(value, now))
        }
    }

    @Test
    fun `printer timestamps of today show their time and still sort by the raw value`() {
        val done = app("done", "COMPLETED", "2026-01-01T10:12:30Z")
        val columns = genericColumns(listOf(done), namespacedKind = true, now = Instant.parse("2026-01-01T18:00:00Z"))
        assertEquals("10:00:00Z", columns.cell("Start", done).text)
        assertEquals("2026-01-01T10:00:00Z", columns.cell("Start", done).sortValue)
        assertEquals("1", columns.cell("Attempts", done).text)

        val tomorrow = genericColumns(listOf(done), namespacedKind = true, now = Instant.parse("2026-01-02T00:00:01Z"))
        assertEquals("2026-01-01T10:12:30Z", tomorrow.cell("Finish", done).text)
    }

    @Test
    fun `printer columns are as wide as their longest text, within bounds`() {
        val done = app("done", "COMPLETED", "2026-01-01T10:12:30Z")
        val weight = { now: String, header: String -> genericColumns(listOf(done), namespacedKind = true, now = Instant.parse(now)).single { it.header == header }.weight }
        val today = "2026-01-01T18:00:00Z"
        // "Suspend" (7) beats its dash; "COMPLETED" plus the status icon is 11; "Attempts" is 8; a time of today is 9.
        assertEquals(7 * 0.12f, weight(today, "Suspend"), 0.001f)
        assertEquals(11 * 0.12f, weight(today, "Status"), 0.001f)
        assertEquals(8 * 0.12f, weight(today, "Attempts"), 0.001f)
        assertEquals(9 * 0.12f, weight(today, "Start"), 0.001f)
        // A full timestamp (20) hits the upper bound; a one-letter column stays at the lower one.
        assertEquals(2.4f, weight("2026-01-05T00:00:00Z", "Start"), 0.001f)
        val tiny = app("tiny", "COMPLETED", "", mapOf("X" to "1")).copy(spark = null)
        assertEquals(0.6f, genericColumns(listOf(tiny), namespacedKind = false).single { it.header == "X" }.weight, 0.001f)
    }
}
