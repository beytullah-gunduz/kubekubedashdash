package com.kubekubedashdash.ui.screens.generic

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.SparkRun
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
}
