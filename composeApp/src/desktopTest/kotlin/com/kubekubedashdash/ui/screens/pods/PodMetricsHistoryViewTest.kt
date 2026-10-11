package com.kubekubedashdash.ui.screens.pods

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.services.metricshistory.GmpTarget
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.NoSourceReason
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.ui.components.ChartSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pure decisions behind [PodMetricsHistorySection]: kinds, view, texts, colours and formatters. */
class PodMetricsHistoryViewTest {

    private fun pod(resources: List<ContainerResources> = emptyList(), hostNetwork: Boolean = false) = PodInfo(
        uid = "pod-a",
        name = "web-0",
        namespace = "team-a",
        status = "Running",
        ready = "1/1",
        restarts = 0,
        age = "1h",
        node = "node-a",
        ip = "",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = emptyList(),
        resources = resources,
        hostNetwork = hostNetwork,
    )

    private val target = GmpTarget("example-project", "us-central1", "example-cluster")
    private val gmp = SourceChoice.Gmp(target, auto = true)
    private val window = QueryWindow(0, 3_600, 30)
    private val ok = SeriesResult.Ok(listOf(Series("app", listOf(SeriesPoint(0, 1.0)))))
    private val failed = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")

    private fun loaded(vararg results: Pair<PodSeriesKind, SeriesResult>) = LoadedHistory(window, mapOf(*results))

    @Test
    fun `a plain pod is asked for CPU, memory and both network directions`() {
        assertEquals(
            listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory, PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx),
            historyKinds(pod(listOf(ContainerResources("app", cpuRequestMillis = 100)))),
        )
    }

    @Test
    fun `a host-network pod is not asked for network`() {
        assertEquals(listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory), historyKinds(pod(hostNetwork = true)))
    }

    @Test
    fun `a CPU limit on any container adds throttling`() {
        val kinds = historyKinds(pod(listOf(ContainerResources("app"), ContainerResources("sidecar", cpuLimitMillis = 500))))
        assertEquals(PodSeriesKind.Throttling, kinds.last())
        assertEquals(5, kinds.size)
    }

    @Test
    fun `each reason to have no source maps to its own live-only hint`() {
        fun hint(reason: NoSourceReason) = assertIs<HistoryView.LiveOnly>(historyView(SourceChoice.None(reason), null)).hint

        assertEquals("", hint(NoSourceReason.NotConnected))
        assertTrue(hint(NoSourceReason.Disabled).contains("metrics history is off"))
        assertTrue(hint(NoSourceReason.NotGkeContext).contains("set this cluster up in Settings"))
        assertTrue(hint(NoSourceReason.IncompleteSetting).contains("settings are incomplete"))
    }

    @Test
    fun `no source stays live-only even with a fetch result`() {
        val view = historyView(SourceChoice.None(NoSourceReason.Disabled), loaded(PodSeriesKind.Cpu to ok))
        assertIs<HistoryView.LiveOnly>(view)
    }

    @Test
    fun `a source with nothing fetched yet is loading`() {
        assertEquals(HistoryView.Loading, historyView(gmp, null))
        assertEquals(HistoryView.Loading, historyView(SourceChoice.Demo, null))
    }

    @Test
    fun `CPU and memory both failing is a source failure`() {
        val view = historyView(gmp, loaded(PodSeriesKind.Cpu to failed, PodSeriesKind.Memory to failed, PodSeriesKind.NetworkRx to ok))
        assertEquals(HistoryView.SourceFailed("Managed Prometheus didn't answer (HTTP 503). Retrying every 30 s; showing live metrics-server data."), view)
    }

    @Test
    fun `only memory failing still shows the charts`() {
        val results = loaded(PodSeriesKind.Cpu to ok, PodSeriesKind.Memory to failed)
        assertEquals(HistoryView.Charts(results), historyView(gmp, results))
    }

    @Test
    fun `only network failing still shows the charts`() {
        val results = loaded(PodSeriesKind.Cpu to ok, PodSeriesKind.Memory to ok, PodSeriesKind.NetworkRx to failed, PodSeriesKind.NetworkTx to failed)
        assertEquals(HistoryView.Charts(results), historyView(gmp, results))
    }

    @Test
    fun `a missing access role names the project and the role`() {
        val message = failureMessage(gmp, SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"))
        assertTrue(message.contains("roles/monitoring.viewer"), message)
        assertTrue(message.contains("example-project"), message)
        assertTrue(message.contains("HTTP 403"), message)
    }

    @Test
    fun `missing container metrics gives the command that turns them on`() {
        val message = failureMessage(gmp, SeriesResult.Failed(HistoryFailure.NoContainerMetrics))
        assertTrue(message.contains("--monitoring=SYSTEM,CADVISOR,KUBELET"), message)
        assertTrue(message.contains("clusters update example-cluster --location=us-central1"), message)
    }

    @Test
    fun `the safe detail reaches the message where the plan shows it and nowhere else`() {
        val shown = setOf(HistoryFailure.Forbidden, HistoryFailure.BadQuery, HistoryFailure.Unavailable)
        HistoryFailure.entries.forEach { failure ->
            val message = failureMessage(gmp, SeriesResult.Failed(failure, "detail-42"))
            assertTrue(message.isNotBlank(), "$failure")
            assertEquals(failure in shown, message.contains("detail-42"), "$failure: $message")
        }
    }

    @Test
    fun `a failure without a Google target gets the generic text`() {
        assertEquals(
            "Metrics history unavailable (Unavailable).",
            failureMessage(SourceChoice.Demo, SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")),
        )
    }

    @Test
    fun `a single chart failure is one short phrase`() {
        assertEquals("gcloud not found", shortFailure(SeriesResult.Failed(HistoryFailure.GcloudUnavailable)))
        assertEquals("gcloud not signed in", shortFailure(SeriesResult.Failed(HistoryFailure.NotSignedIn)))
        assertEquals("No access", shortFailure(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403")))
        assertEquals("No data", shortFailure(SeriesResult.Failed(HistoryFailure.NoContainerMetrics)))
        assertEquals("Query rejected (bad_data)", shortFailure(SeriesResult.Failed(HistoryFailure.BadQuery, "bad_data")))
        assertEquals("Not answering (timeout)", shortFailure(SeriesResult.Failed(HistoryFailure.Unavailable, "timeout")))
    }

    @Test
    fun `values are formatted for their unit`() {
        assertEquals("250m", formatCoresValue(0.25))
        assertEquals("1.50 cores", formatCoresValue(1.5))
        assertEquals("12%", formatFractionValue(0.123))
        assertEquals("2 MiB/s", formatRateValue(2_097_152.0))
        assertEquals("512 KiB", formatBytesValue(524_288.0))
    }

    @Test
    fun `containers get a stable sorted colour index`() {
        assertEquals(mapOf("a" to 0, "b" to 1), containerColorIndex(listOf("b", "a", "b")))
    }

    private fun lines(vararg names: String) = names.map { ChartSeries(it, Color.Red, emptyList()) }

    @Test
    fun `a lone container line gets its request and limit as guide lines`() {
        val p = pod(listOf(ContainerResources("app", cpuRequestMillis = 250, cpuLimitMillis = 500, memoryRequestBytes = 1_048_576, memoryLimitBytes = 2_097_152)))
        val cpu = usageReferences(p, lines("app"), PodSeriesKind.Cpu)
        assertEquals(listOf("request" to 0.25, "limit" to 0.5), cpu.map { it.label to it.value })
        assertEquals(listOf(true, false), cpu.map { it.dashed })
        val memory = usageReferences(p, lines("app"), PodSeriesKind.Memory)
        assertEquals(listOf(1_048_576.0, 2_097_152.0), memory.map { it.value })
    }

    @Test
    fun `several lines or an unknown container get no guide lines`() {
        val p = pod(listOf(ContainerResources("app", cpuRequestMillis = 250), ContainerResources("sidecar", cpuRequestMillis = 100)))
        assertEquals(emptyList(), usageReferences(p, lines("app", "sidecar"), PodSeriesKind.Cpu))
        assertEquals(emptyList(), usageReferences(p, lines("other"), PodSeriesKind.Cpu))
        assertEquals(emptyList(), usageReferences(p, emptyList(), PodSeriesKind.Cpu))
    }
}
