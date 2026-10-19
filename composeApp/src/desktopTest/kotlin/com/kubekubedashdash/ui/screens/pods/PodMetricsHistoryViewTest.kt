package com.kubekubedashdash.ui.screens.pods

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.services.metricshistory.GmpTarget
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.InClusterTarget
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
import kotlin.test.assertFalse
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
    private val detected = SourceChoice.InCluster(InClusterTarget("monitoring", "prometheus-server", "80", https = false, pathPrefix = ""), auto = true)
    private val typed = detected.copy(auto = false)
    private val window = QueryWindow(0, 3_600, 30)
    private val ok = SeriesResult.Ok(listOf(Series("app", listOf(SeriesPoint(0, 1.0)))))
    private val failed = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")
    private val empty = SeriesResult.Ok(emptyList())
    private val pointless = SeriesResult.Ok(listOf(Series("app", emptyList())))

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
        // Auto found no Prometheus: no line, the section looks as it did before metrics history existed.
        assertEquals("", hint(NoSourceReason.NothingFound))
        assertEquals("", hint(NoSourceReason.CannotSearch))
        assertEquals("", hint(NoSourceReason.SearchFailed))
        assertTrue(hint(NoSourceReason.IncompleteSetting).contains("settings are incomplete"))
    }

    @Test
    fun `an incomplete setting names no mode, since it cannot tell which one is incomplete`() {
        val hint = assertIs<HistoryView.LiveOnly>(historyView(SourceChoice.None(NoSourceReason.IncompleteSetting), null)).hint
        assertEquals("Live data only — this cluster's metrics history settings are incomplete (Settings → Metrics history).", hint)
        assertFalse(hint.contains("Managed Prometheus"), hint)
    }

    @Test
    fun `no source stays live-only even with a fetch result`() {
        val view = historyView(SourceChoice.None(NoSourceReason.Disabled), loaded(PodSeriesKind.Cpu to ok))
        assertIs<HistoryView.LiveOnly>(view)
    }

    @Test
    fun `a source with nothing fetched yet is loading`() {
        assertEquals(HistoryView.Loading, historyView(gmp, null))
        assertEquals(HistoryView.Loading, historyView(detected, null))
    }

    @Test
    fun `a search that is still under way is loading, so the live charts stay under the loading line`() {
        assertEquals(HistoryView.Loading, historyView(SourceChoice.Detecting, null))
        // Whatever was fetched before the search began does not belong to it.
        assertEquals(HistoryView.Loading, historyView(SourceChoice.Detecting, loaded(PodSeriesKind.Cpu to ok)))
    }

    @Test
    fun `a detected or typed in-cluster source is loading until its first fetch, then draws like any other`() {
        listOf(detected, typed).forEach { choice ->
            assertEquals(HistoryView.Loading, historyView(choice, null))
            val results = loaded(PodSeriesKind.Cpu to ok, PodSeriesKind.Memory to ok)
            assertEquals(HistoryView.Charts(results), historyView(choice, results))
        }
    }

    private val liveRow = listOf(PodMetricsSnapshot(1_000, 100, 1_048_576), PodMetricsSnapshot(2_000, 200, 2_097_152))

    @Test
    fun `with history points the area is the grid, carrying the latest live snapshot`() {
        val results = loaded(PodSeriesKind.Cpu to ok)
        assertEquals(PodArea.Grid(results, null), podArea(HistoryView.Charts(results), sourceKnown = true, live = liveRow))
        assertEquals(PodArea.Grid(results, null), podArea(HistoryView.Charts(results), sourceKnown = true, live = emptyList()))
    }

    @Test
    fun `loading with a source known is the skeleton grid with the latest live snapshot`() {
        assertEquals(PodArea.Grid(null, liveRow.last()), podArea(HistoryView.Loading, sourceKnown = true, live = liveRow))
    }

    @Test
    fun `loading without a source yet is the live row`() {
        assertEquals(PodArea.Live(HistoryView.Loading, liveRow), podArea(HistoryView.Loading, sourceKnown = false, live = liveRow))
    }

    @Test
    fun `a live fallback is the live row whatever the source`() {
        val fallback = HistoryView.LiveFallback("down", transient = true)
        assertEquals(PodArea.Live(fallback, liveRow), podArea(fallback, sourceKnown = true, live = liveRow))
        assertEquals(PodArea.Live(fallback, emptyList()), podArea(fallback, sourceKnown = true, live = emptyList()))
    }

    @Test
    fun `CPU and memory both failing is a source failure`() {
        val view = historyView(gmp, loaded(PodSeriesKind.Cpu to failed, PodSeriesKind.Memory to failed, PodSeriesKind.NetworkRx to ok))
        assertEquals(HistoryView.LiveFallback("Managed Prometheus didn't answer (HTTP 503). Retrying every 30 s; showing live metrics-server data.", transient = true), view)
    }

    @Test
    fun `a finished pod's fallback drops the live charts and the live-data clause`() {
        val noHistory = historyView(gmp, loaded(PodSeriesKind.Cpu to SeriesResult.Ok(emptyList()), PodSeriesKind.Memory to SeriesResult.Ok(emptyList())), finished = true)
        assertEquals(HistoryView.LiveFallback(FINISHED_POD_NO_HISTORY_NOTE, transient = false, liveCharts = false), noHistory)
        val failing = historyView(gmp, loaded(PodSeriesKind.Cpu to failed, PodSeriesKind.Memory to failed), finished = true)
        assertEquals(HistoryView.LiveFallback("Managed Prometheus didn't answer (HTTP 503). Retrying every 30 s.", transient = true, liveCharts = false), failing)
    }

    @Test
    fun `only a pod that has run to its end counts as finished`() {
        fun phase(p: String) = podFinished(pod().copy(phase = p))
        assertTrue(phase("Succeeded"))
        assertTrue(phase("Failed"))
        listOf("Running", "Pending", "Unknown", "").forEach { assertFalse(phase(it), it) }
    }

    @Test
    fun `a fallback is transient only when the failure behind it is`() {
        fun fallback(cpu: SeriesResult, memory: SeriesResult) = assertIs<HistoryView.LiveFallback>(historyView(gmp, loaded(PodSeriesKind.Cpu to cpu, PodSeriesKind.Memory to memory)))

        assertTrue(fallback(failed, failed).transient)
        // Memory's failure explains when CPU merely has no point.
        assertTrue(fallback(empty, failed).transient)
        assertFalse(fallback(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"), failed).transient)
        assertFalse(fallback(SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported"), failed).transient)
        assertFalse(fallback(SeriesResult.Failed(HistoryFailure.Unavailable, "bad request"), failed).transient)
        // No failure behind "no history": nothing for Retry to fix.
        val none = fallback(empty, pointless)
        assertEquals(NO_HISTORY_NOTE, none.message)
        assertFalse(none.transient)
    }

    @Test
    fun `CPU and memory answering without a point fall back to the live charts`() {
        assertEquals(
            HistoryView.LiveFallback(NO_HISTORY_NOTE),
            historyView(gmp, loaded(PodSeriesKind.Cpu to empty, PodSeriesKind.Memory to pointless, PodSeriesKind.NetworkRx to ok)),
        )
    }

    @Test
    fun `with no point to draw a failure explains instead of the no-history line`() {
        assertEquals(
            HistoryView.LiveFallback("Managed Prometheus didn't answer (HTTP 503). Retrying every 30 s; showing live metrics-server data.", transient = true),
            historyView(gmp, loaded(PodSeriesKind.Cpu to empty, PodSeriesKind.Memory to failed)),
        )
    }

    @Test
    fun `one point in CPU or memory is enough for the charts`() {
        val memoryOnly = loaded(PodSeriesKind.Cpu to empty, PodSeriesKind.Memory to ok)
        assertEquals(HistoryView.Charts(memoryOnly), historyView(gmp, memoryOnly))
        val cpuOnly = loaded(PodSeriesKind.Cpu to ok, PodSeriesKind.Memory to failed)
        assertEquals(HistoryView.Charts(cpuOnly), historyView(gmp, cpuOnly))
    }

    @Test
    fun `the loop asks again for a new window, a failure or nothing to draw, and only then`() {
        val answered = loaded(PodSeriesKind.Cpu to ok, PodSeriesKind.Memory to ok, PodSeriesKind.NetworkRx to empty)
        assertFalse(needsFetch(answered, window), "same window, all answered, CPU and memory have points")
        assertTrue(needsFetch(null, window))
        assertTrue(needsFetch(answered, window.copy(endEpochSec = window.endEpochSec + 30)))
        assertTrue(needsFetch(loaded(PodSeriesKind.Cpu to ok, PodSeriesKind.Memory to failed), window))
        // A new pod has no point yet; a 24 h window would not move for 720 s, so emptiness alone asks again.
        assertTrue(needsFetch(loaded(PodSeriesKind.Cpu to empty, PodSeriesKind.Memory to pointless), window))
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
    fun `a role that cannot use services proxy is told which verb and namespace it needs, and that view does not have it`() {
        val message = failureMessage(detected, SeriesResult.Failed(HistoryFailure.NoProxyAccess))
        assertEquals(
            "Your Kubernetes role can't reach Prometheus through the API server: it needs get on services/proxy " +
                "in namespace monitoring (granted by edit or admin, not view). Showing live metrics-server data.",
            message,
        )
    }

    @Test
    fun `a Prometheus that refuses names itself and the safe detail, and hints at an authenticating proxy`() {
        assertEquals(
            "Prometheus at monitoring/prometheus-server refused the request (HTTP 401) — it may sit behind an authenticating proxy.",
            failureMessage(detected, SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 401")),
        )
    }

    @Test
    fun `a vanished Service says it will search again only when the target was detected`() {
        val gone = SeriesResult.Failed(HistoryFailure.ServiceGone)
        assertEquals(
            "Service monitoring/prometheus-server no longer exists. Searching again; showing live metrics-server data.",
            failureMessage(detected, gone),
        )
        assertEquals(
            "Service monitoring/prometheus-server no longer exists. Check Settings → Metrics history.",
            failureMessage(typed, gone),
        )
    }

    @Test
    fun `a wrong path points at the path prefix setting`() {
        assertEquals(
            "monitoring/prometheus-server answered 404 at the Prometheus API path — check the path prefix in Settings → Metrics history.",
            failureMessage(typed, SeriesResult.Failed(HistoryFailure.WrongPath)),
        )
    }

    @Test
    fun `a Prometheus without cAdvisor metrics says what its scrape config needs`() {
        assertEquals(
            "This Prometheus has no cAdvisor container metrics (container_cpu_usage_seconds_total): " +
                "its scrape config must include the kubelet's /metrics/cadvisor.",
            failureMessage(detected, SeriesResult.Failed(HistoryFailure.NoContainerMetrics)),
        )
    }

    @Test
    fun `an in-cluster source that does not answer or rejects a query says Prometheus, not Managed Prometheus`() {
        assertEquals(
            "Prometheus didn't answer (HTTP 503). Retrying every 30 s; showing live metrics-server data.",
            failureMessage(detected, SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")),
        )
        assertEquals(
            "Prometheus rejected the query (bad_data).",
            failureMessage(detected, SeriesResult.Failed(HistoryFailure.BadQuery, "bad_data")),
        )
    }

    @Test
    fun `an in-cluster choice shows its detail where the plan shows it, names only the validated target, and never the gcloud texts`() {
        val shown = setOf(HistoryFailure.Forbidden, HistoryFailure.BadQuery, HistoryFailure.Unavailable)
        listOf(detected, typed).forEach { choice ->
            HistoryFailure.entries.forEach { failure ->
                val message = failureMessage(choice, SeriesResult.Failed(failure, "detail-42"))
                assertTrue(message.isNotBlank(), "$failure")
                assertEquals(failure in shown, message.contains("detail-42"), "$failure: $message")
                assertFalse(message.contains("gcloud"), "$failure: $message")
                assertFalse(message.contains("Managed Prometheus"), "$failure: $message")
            }
        }
        // GcloudUnavailable and NotSignedIn cannot come from a proxy source: they get the text that names nothing.
        assertEquals(
            "Metrics history unavailable (GcloudUnavailable).",
            failureMessage(detected, SeriesResult.Failed(HistoryFailure.GcloudUnavailable)),
        )
        assertEquals(
            "Metrics history unavailable (NotSignedIn).",
            failureMessage(typed, SeriesResult.Failed(HistoryFailure.NotSignedIn)),
        )
    }

    @Test
    fun `the proxy failures cannot come from Managed Prometheus and get the text that names nothing`() {
        listOf(HistoryFailure.NoProxyAccess, HistoryFailure.ServiceGone, HistoryFailure.WrongPath).forEach { failure ->
            assertEquals("Metrics history unavailable ($failure).", failureMessage(gmp, SeriesResult.Failed(failure, "detail-42")))
        }
    }

    @Test
    fun `a failure without a target gets the generic text`() {
        assertEquals(
            "Metrics history unavailable (Unavailable).",
            failureMessage(SourceChoice.Detecting, SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")),
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
    fun `the proxy failures are one short phrase each, with no detail`() {
        assertEquals("No services/proxy access", shortFailure(SeriesResult.Failed(HistoryFailure.NoProxyAccess, "detail-42")))
        assertEquals("Service gone", shortFailure(SeriesResult.Failed(HistoryFailure.ServiceGone, "detail-42")))
        assertEquals("Wrong path", shortFailure(SeriesResult.Failed(HistoryFailure.WrongPath, "detail-42")))
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
