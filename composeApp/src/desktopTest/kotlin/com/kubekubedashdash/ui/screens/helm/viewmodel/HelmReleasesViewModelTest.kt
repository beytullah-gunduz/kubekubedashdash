package com.kubekubedashdash.ui.screens.helm.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.helm.HELM_FORBIDDEN_MESSAGE
import com.kubekubedashdash.helm.HelmDecoded
import com.kubekubedashdash.helm.HelmDriver
import com.kubekubedashdash.helm.HelmReleaseCodec
import com.kubekubedashdash.helm.HelmReleaseRepository
import com.kubekubedashdash.helm.HelmReleaseSummary
import com.kubekubedashdash.helm.HelmRevisionRef
import com.kubekubedashdash.helm.HelmRevisions
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.shutdownCleanly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Helm list view model over fake source flows and a repository over a fake fetch: no
 * cluster, no mock server. The VM's state is collected for the whole test, the way the
 * screen's collectAsState does (see PodsScreenViewModelScopeTest).
 */
class HelmReleasesViewModelTest {

    private lateinit var scope: CoroutineScope
    private lateinit var secrets: MutableStateFlow<ResourceState<List<HelmRevisionRef>>>
    private lateinit var configMaps: MutableStateFlow<ResourceState<List<HelmRevisionRef>>>
    private lateinit var vm: HelmReleasesViewModel
    private var stateCollector: Job? = null
    private var selectedCollector: Job? = null

    /** Per object name: what the fake fetch returns instead of the default decodable payload. */
    private val overrides = ConcurrentHashMap<String, () -> String?>()
    private val fetchCount = AtomicInteger(0)

    /** Fetches wait on this; a test closes it (count 1) to hold every decode, then opens it. */
    @Volatile
    private var gate = CountDownLatch(0)

    @BeforeTest
    fun setUp() {
        buildViewModel(summaryCapacity = 1_024)
    }

    /** [retryDelaysMs]: tiny by default, so a test that makes a fetch fail doesn't wait out the production delays. */
    private fun buildViewModel(summaryCapacity: Int, retryDelaysMs: List<Long> = listOf(10L, 10L, 10L)) {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        secrets = MutableStateFlow(ResourceState.Loading)
        configMaps = MutableStateFlow(ResourceState.Loading)
        val repository = HelmReleaseRepository(
            scope = scope,
            fetch = { ref ->
                fetchCount.incrementAndGet()
                gate.await()
                val override = overrides[ref.objectName]
                if (override != null) override() else defaultPayload(ref)
            },
            summaryCapacity = summaryCapacity,
        )
        vm = HelmReleasesViewModel(secrets, configMaps, repository, retryDelaysMs)
        stateCollector = scope.launch { vm.state.collect {} }
        selectedCollector = scope.launch { vm.selected.collect {} }
    }

    @AfterTest
    fun tearDown() {
        gate.countDown()
        shutdownCleanly(scope, vm.viewModelScope, label = "HelmReleasesViewModelTest")
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun ref(
        release: String,
        revision: Int,
        status: String = "deployed",
        namespace: String = "default",
        driver: HelmDriver = HelmDriver.SECRET,
    ) = HelmRevisionRef(
        driver = driver,
        namespace = namespace,
        objectName = "sh.helm.release.v1.$release.v$revision",
        uid = "uid-$namespace-$release-$revision",
        resourceVersion = "1",
        releaseName = release,
        revision = revision,
        status = status,
        createdAtEpochSeconds = 1_700_000_000L + revision,
        modifiedAtEpochSeconds = null,
        creationTimestamp = null,
        epoch = 1L,
    )

    private fun payload(chart: String, version: String, appVersion: String): String = HelmReleaseCodec.encode(
        """{"name":"$chart","info":{"status":"deployed","description":"Install complete"},""" +
            """"chart":{"metadata":{"name":"$chart","version":"$version","appVersion":"$appVersion"}}}""",
    )

    /** A decodable release whose chart is named after the release, so each row's cells are predictable. */
    private fun defaultPayload(ref: HelmRevisionRef): String = payload(ref.releaseName, "1.0.${ref.revision}", "app-${ref.revision}")

    private fun publish(vararg refs: HelmRevisionRef) {
        secrets.value = ResourceState.Success(refs.toList())
    }

    private suspend fun awaitReady(predicate: (HelmListState.Ready) -> Boolean = { true }): HelmListState.Ready = withTimeout(10_000) {
        vm.state.filterIsInstance<HelmListState.Ready>().first(predicate)
    }

    private fun HelmListState.Ready.row(name: String): HelmReleaseRow = rows.first { it.name == name }

    private fun searchRow(ref: HelmRevisionRef, summary: HelmSummaryState): HelmReleaseRow = HelmReleaseRow(HelmRevisions.group(listOf(ref)).single(), summary)

    // ── state ────────────────────────────────────────────────────────────

    @Test
    fun `the list is Loading until the secrets load`() = runBlocking {
        assertEquals(HelmListState.Loading, vm.state.value)

        // The ConfigMap list alone never opens the list: Secrets are the default driver.
        configMaps.value = ResourceState.Success(emptyList())
        assertNull(withTimeoutOrNull(300) { vm.state.first { it !is HelmListState.Loading } })

        publish(ref("frontend", 1))
        val ready = awaitReady()
        assertEquals(listOf("frontend"), ready.rows.map { it.name })
        assertNull(ready.warning)
    }

    @Test
    fun `one row per release shows its latest revision`() = runBlocking {
        publish(
            ref("frontend", 1, status = "superseded"),
            ref("frontend", 3, status = "deployed"),
            ref("frontend", 2, status = "failed"),
            ref("backend-api", 1),
            ref("backend-api", 1, namespace = "production"),
        )

        val ready = awaitReady()

        assertEquals(
            listOf("default/backend-api", "default/frontend", "production/backend-api"),
            ready.rows.map { "${it.namespace}/${it.name}" },
        )
        val frontend = ready.row("frontend")
        assertEquals(3, frontend.latest.revision)
        assertEquals("deployed", frontend.status)
        assertEquals(listOf(3, 2, 1), frontend.group.revisions.map { it.revision })
        assertEquals("secret:default/frontend", frontend.uid)
    }

    @Test
    fun `a ConfigMap-driver release is a row of its own, and a failed list is a warning`() = runBlocking {
        publish(ref("frontend", 1))
        configMaps.value = ResourceState.Success(listOf(ref("metrics-stack", 1, namespace = "monitoring", driver = HelmDriver.CONFIGMAP)))

        val ready = awaitReady { it.rows.size == 2 }
        assertEquals("configmap:monitoring/metrics-stack", ready.row("metrics-stack").uid)

        configMaps.value = ResourceState.Error("the ConfigMap list went wrong")
        val warned = awaitReady { it.warning != null }
        assertEquals(listOf("frontend"), warned.rows.map { it.name })
    }

    @Test
    fun `Chart is Decoding, then Ready once the summary lands`() = runBlocking {
        gate = CountDownLatch(1)
        publish(ref("frontend", 1))

        val decoding = awaitReady()
        assertEquals(HelmSummaryState.Decoding, decoding.row("frontend").summary)

        gate.countDown()
        val ready = awaitReady { it.row("frontend").summary is HelmSummaryState.Ready }
        val summary = assertIs<HelmSummaryState.Ready>(ready.row("frontend").summary).summary
        assertEquals("frontend-1.0.1", summary.chart)
        assertEquals("app-1", summary.appVersion)
    }

    @Test
    fun `a bad payload or a failing fetch makes only its own row Unreadable`() = runBlocking {
        overrides["sh.helm.release.v1.garbled.v1"] = { "!!not base64!!" }
        overrides["sh.helm.release.v1.unreachable.v1"] = { throw IllegalStateException("connection reset by example peer") }
        overrides["sh.helm.release.v1.vanished.v1"] = { null }
        publish(ref("frontend", 1), ref("garbled", 1), ref("unreachable", 1), ref("vanished", 1))

        val ready = awaitReady { r -> r.rows.none { it.summary == HelmSummaryState.Decoding } }

        assertIs<HelmSummaryState.Ready>(ready.row("frontend").summary)
        assertEquals(
            HelmSummaryState.Unreadable("The release payload is not valid base64."),
            ready.row("garbled").summary,
        )
        // A fetch failure shows the fixed text, never the exception's own message, and is
        // marked transient (the view model asked again before giving up).
        assertEquals(
            HelmSummaryState.Unreadable("Couldn't read this release from the cluster.", transient = true),
            ready.row("unreachable").summary,
        )
        assertEquals(HelmSummaryState.Gone, ready.row("vanished").summary)
        assertTrue(stateCollector?.isActive == true, "the state collector must survive the failures")
    }

    @Test
    fun `a forbidden secrets list is a Failed state with the fixed message`() = runBlocking {
        val raw = "Failure executing: GET at: https://example-cluster.test/api/v1/secrets. Message: secrets is forbidden: " +
            "User \"dev@example.com\" cannot list resource \"secrets\". Received status: " +
            "Status(apiVersion=v1, code=403, kind=Status, reason=Forbidden, status=Failure)."
        secrets.value = ResourceState.Error(raw)

        val failed = withTimeout(10_000) { vm.state.first { it is HelmListState.Failed } }

        val f = assertIs<HelmListState.Failed>(failed)
        assertTrue(f.forbidden)
        assertEquals(HELM_FORBIDDEN_MESSAGE, f.message)
        assertFalse("dev@example.com" in f.message)
    }

    @Test
    fun `a non-403 failure keeps the raw text and is not marked forbidden`() = runBlocking {
        secrets.value = ResourceState.Error("connection refused by example-cluster")

        val failed = withTimeout(10_000) { vm.state.first { it is HelmListState.Failed } }

        assertEquals(HelmListState.Failed("connection refused by example-cluster", forbidden = false), failed)
    }

    @Test
    fun `a revision that leaves the list is pruned and decoded again when it returns`() {
        // Capacity 0: the repository keeps nothing, so a second fetch of the same revision can
        // only come from the view model having dropped its own entry for it.
        tearDown()
        buildViewModel(summaryCapacity = 0)
        runBlocking {
            publish(ref("frontend", 1), ref("backend-api", 1))
            awaitReady { r -> r.rows.size == 2 && r.rows.all { it.summary is HelmSummaryState.Ready } }
            assertEquals(2, fetchCount.get())

            // A namespace-scope change: backend-api is no longer covered.
            publish(ref("frontend", 1))
            awaitReady { it.rows.size == 1 }
            assertEquals(2, fetchCount.get(), "frontend keeps its decoded state")

            publish(ref("frontend", 1), ref("backend-api", 1))
            awaitReady { r -> r.rows.size == 2 && r.rows.all { it.summary is HelmSummaryState.Ready } }
            assertEquals(3, fetchCount.get(), "backend-api was decoded again")
        }
    }

    // ── transient failures ───────────────────────────────────────────────

    private suspend fun awaitFetches(count: Int) = withTimeout(10_000) {
        while (fetchCount.get() < count) delay(5)
    }

    private suspend fun awaitUnreadable(name: String): HelmSummaryState.Unreadable {
        val ready = awaitReady { it.rows.any { r -> r.name == name && r.summary is HelmSummaryState.Unreadable } }
        return assertIs<HelmSummaryState.Unreadable>(ready.row(name).summary)
    }

    @Test
    fun `a fetch that fails twice and then succeeds ends Ready without ever reading as unreadable`() = runBlocking {
        val attempts = AtomicInteger(0)
        overrides["sh.helm.release.v1.flaky.v1"] = {
            if (attempts.incrementAndGet() <= 2) throw IllegalStateException("connection reset by example peer") else payload("flaky", "1.0.0", "app-1")
        }
        val seen = CopyOnWriteArrayList<HelmSummaryState>()
        scope.launch { vm.state.filterIsInstance<HelmListState.Ready>().collect { r -> r.rows.firstOrNull()?.let { seen += it.summary } } }
        publish(ref("flaky", 1))

        val ready = awaitReady { it.row("flaky").summary is HelmSummaryState.Ready }

        assertEquals("flaky-1.0.0", assertIs<HelmSummaryState.Ready>(ready.row("flaky").summary).summary.chart)
        assertEquals(3, fetchCount.get())
        assertTrue(seen.none { it is HelmSummaryState.Unreadable }, "the row stays Decoding while it retries, saw $seen")
    }

    @Test
    fun `a fetch that always fails ends Unreadable and transient after exactly four fetches`() = runBlocking {
        overrides["sh.helm.release.v1.down.v1"] = { throw IllegalStateException("connection reset by example peer") }
        publish(ref("down", 1))

        val unreadable = awaitUnreadable("down")

        assertEquals(HelmSummaryState.Unreadable("Couldn't read this release from the cluster.", transient = true), unreadable)
        // One try plus the three delays; give a stray fifth try time to show up (each delay is 10 ms).
        delay(200)
        assertEquals(4, fetchCount.get())
    }

    @Test
    fun `a malformed payload is not retried`() = runBlocking {
        overrides["sh.helm.release.v1.garbled.v1"] = { "!!not base64!!" }
        publish(ref("garbled", 1))

        val unreadable = awaitUnreadable("garbled")

        assertEquals(HelmSummaryState.Unreadable("The release payload is not valid base64.", transient = false), unreadable)
        delay(200)
        assertEquals(1, fetchCount.get())
    }

    @Test
    fun `a revision that leaves the list during the retries is no longer retried and leaves no entry`() {
        // Capacity 0: the repository keeps nothing, so a stale entry left behind by the view
        // model is the only thing that could make the returning row anything but Decoding.
        tearDown()
        buildViewModel(summaryCapacity = 0, retryDelaysMs = listOf(150L, 150L, 150L))
        overrides["sh.helm.release.v1.down.v1"] = { throw IllegalStateException("connection reset by example peer") }
        runBlocking {
            publish(ref("down", 1))
            awaitFetches(1)

            // A namespace-scope change drops the release while its first retry is still waiting.
            publish(ref("frontend", 1))
            awaitReady { r -> r.rows.map { it.name } == listOf("frontend") }
            delay(50)
            val atRemoval = fetchCount.get()
            delay(600)
            assertEquals(atRemoval, fetchCount.get(), "the dropped release must not be retried")

            publish(ref("frontend", 1), ref("down", 1))
            val back = awaitReady { r -> r.rows.any { it.name == "down" } }
            assertEquals(HelmSummaryState.Decoding, back.row("down").summary)
        }
    }

    @Test
    fun `a decode that finishes after its revision left the list leaves no entry`() {
        tearDown()
        buildViewModel(summaryCapacity = 0)
        runBlocking {
            gate = CountDownLatch(1)
            publish(ref("down", 1))
            awaitFetches(1)
            publish(ref("frontend", 1))
            awaitReady { r -> r.rows.map { it.name } == listOf("frontend") }

            // The dropped release's decode now completes into a list that no longer holds it.
            gate.countDown()
            awaitReady { r -> r.rows.all { it.summary is HelmSummaryState.Ready } }
            delay(100)

            gate = CountDownLatch(1)
            publish(ref("frontend", 1), ref("down", 1))
            val back = awaitReady { r -> r.rows.any { it.name == "down" } }
            assertEquals(HelmSummaryState.Decoding, back.row("down").summary)
        }
    }

    // ── selection ────────────────────────────────────────────────────────

    @Test
    fun `selecting a row toggles it and clearSelection clears it`() = runBlocking {
        publish(ref("frontend", 1), ref("backend-api", 1))
        val row = awaitReady().row("frontend")

        vm.select(row)
        assertEquals("secret:default/frontend", withTimeout(10_000) { vm.selected.first { it != null } }?.uid)

        vm.select(row)
        withTimeout(10_000) { vm.selected.first { it == null } }

        vm.select(row)
        withTimeout(10_000) { vm.selected.first { it != null } }
        vm.clearSelection()
        withTimeout(10_000) { vm.selected.first { it == null } }
        assertNull(vm.selected.value)
    }

    @Test
    fun `the selection survives a new revision of the selected release`() = runBlocking {
        publish(ref("frontend", 1))
        vm.select(awaitReady().row("frontend"))
        withTimeout(10_000) { vm.selected.first { it?.latest?.revision == 1 } }

        // An upgrade is a new storage object with a new uid; the row's identity is the release.
        publish(ref("frontend", 1, status = "superseded"), ref("frontend", 2))

        val selected = withTimeout(10_000) { vm.selected.first { it?.latest?.revision == 2 } }
        assertEquals("secret:default/frontend", selected?.uid)
    }

    @Test
    fun `the selection is kept while the list reloads`() = runBlocking {
        publish(ref("frontend", 1))
        vm.select(awaitReady().row("frontend"))
        withTimeout(10_000) { vm.selected.first { it != null } }

        secrets.value = ResourceState.Loading
        withTimeout(10_000) { vm.state.first { it is HelmListState.Loading } }
        assertNotNull(vm.selected.value, "a reconnect's Loading must not drop the selection")

        publish(ref("frontend", 1))
        awaitReady()
        assertEquals("secret:default/frontend", withTimeout(10_000) { vm.selected.first { it != null } }?.uid)
    }

    @Test
    fun `the detail expanded flag follows setDetailExpanded`() {
        assertFalse(vm.detailExpanded.value)
        vm.setDetailExpanded(true)
        assertTrue(vm.detailExpanded.value)
        vm.setDetailExpanded(false)
        assertFalse(vm.detailExpanded.value)
    }

    // ── search and mapping ───────────────────────────────────────────────

    @Test
    fun `search matches name, namespace and status, and the chart and app version once decoded`() {
        val summary = HelmReleaseSummary(
            chartName = "nginx",
            chartVersion = "1.2.3",
            appVersion = "1.25.4",
            description = "Install complete",
            status = "deployed",
            firstDeployed = Instant.EPOCH,
            lastDeployed = Instant.EPOCH,
        )
        val ready = searchRow(ref("frontend", 2, status = "pending-upgrade", namespace = "production"), HelmSummaryState.Ready(summary))
        val decoding = searchRow(ref("frontend", 2, status = "pending-upgrade", namespace = "production"), HelmSummaryState.Decoding)

        assertTrue(matchesHelmSearch(ready, ""))
        assertTrue(matchesHelmSearch(ready, "  "))
        assertTrue(matchesHelmSearch(ready, "FRONT"))
        assertTrue(matchesHelmSearch(ready, "produc"))
        assertTrue(matchesHelmSearch(ready, "pending"))
        assertTrue(matchesHelmSearch(ready, "nginx-1.2"))
        assertTrue(matchesHelmSearch(ready, "1.25"))
        assertFalse(matchesHelmSearch(ready, "redis"))

        // Before the decode lands only the label-derived fields can match.
        assertTrue(matchesHelmSearch(decoding, "frontend"))
        assertFalse(matchesHelmSearch(decoding, "nginx"))
        assertFalse(matchesHelmSearch(decoding, "1.25"))
    }

    @Test
    fun `a decode outcome maps to the row's summary state`() {
        val summary = HelmReleaseSummary("nginx", "1.2.3", "1.25.4", "", "deployed", null, null)
        assertEquals(HelmSummaryState.Ready(summary), HelmDecoded.Ok(summary).toSummaryState())
        assertEquals(HelmSummaryState.Unreadable("bad", transient = true), HelmDecoded.Failed("bad", transient = true).toSummaryState())
        assertEquals(HelmSummaryState.Unreadable("bad", transient = false), HelmDecoded.Failed("bad").toSummaryState())
        assertEquals(HelmSummaryState.Gone, HelmDecoded.Missing.toSummaryState())
    }
}
