package com.kubekubedashdash.ui.modals.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.util.GcpProject
import com.kubekubedashdash.util.GkeCluster
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives [GkeDiscoveryViewModel] through the real wizard flow (scan → pick →
 * import) against [FakeGkeDiscoveryGateway], covering the cancellation and
 * generation-guard behavior of `90c3b33` that was previously only verifiable
 * by hand.
 *
 * No `Dispatchers.setMain` here (plan D3): `kotlinx-coroutines-swing` is on
 * the classpath, so `viewModelScope`'s Main dispatcher resolves to the Swing
 * dispatcher and works in a plain JVM test. Tests sequence real concurrency
 * with [CompletableDeferred] gates and bounded [withTimeout] awaits — never
 * `Thread.sleep`.
 *
 * The fake gateway also absorbs the preference persistence: these tests must
 * not initialize the real settings DataStore (which would overwrite the
 * developer's saved project selection) nor touch the real kubeconfig.
 */
class GkeDiscoveryViewModelTest {

    private val clusterA = GkeCluster("cluster-a", "us-central1", "example-project", "RUNNING")
    private val clusterB = GkeCluster("cluster-b", "us-central1", "example-project", "RUNNING")
    private val clusterC = GkeCluster("cluster-c", "us-central1", "example-project", "RUNNING")

    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var reactiveClient: ReactiveKubeClient
    private lateinit var kubeconfigFile: File
    private lateinit var fake: FakeGkeDiscoveryGateway
    private lateinit var vm: GkeDiscoveryViewModel

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        reactiveClient = ReactiveKubeClient(scope, manager)
        kubeconfigFile = File.createTempFile("gke-discovery-test-kubeconfig", ".yaml").apply {
            writeText("apiVersion: v1\nkind: Config\n")
            deleteOnExit()
        }
        fake = FakeGkeDiscoveryGateway(kubeconfigFile)
        fake.clusters = mapOf("example-project" to listOf(clusterA, clusterB, clusterC))
        vm = GkeDiscoveryViewModel(reactiveClient, fake)
    }

    @AfterTest
    fun tearDown() {
        vm.viewModelScope.cancel()
        scope.cancel()
        manager.close()
        kubeconfigFile.delete()
    }

    /**
     * Walks the wizard to PICK_CLUSTERS the way the UI does. Mandatory
     * sequencing (plan WS1 step 6): construction launches `retryLoad()` on
     * Dispatchers.IO which asynchronously assigns the remembered selection, so
     * a test must await both the Loaded state AND the recalled selection
     * before mutating the selection — otherwise the late-arriving load can
     * overwrite the test's toggle and `startScan()` returns silently. The
     * recalled `seed-project` selection is the observable signal that the
     * load's selection write has landed.
     */
    private suspend fun driveToPickClusters() {
        withTimeout(5_000) { vm.projectLoadState.first { it is ProjectLoadState.Loaded } }
        withTimeout(5_000) { vm.selectedProjects.first { "seed-project" in it } }
        vm.toggleProject("example-project")
        vm.startScan()
        withTimeout(5_000) { vm.step.first { it == GkeDiscoveryStep.PICK_CLUSTERS } }
    }

    private suspend fun awaitStep(step: GkeDiscoveryStep) {
        withTimeout(5_000) { vm.step.first { it == step } }
    }

    private fun rowStates(): Map<String, GkeImportRowState> = vm.importRows.value.associate { it.cluster.name to it.state }

    // Probe kept from WS1 step 1: proves the ViewModel constructs and its
    // init-time load completes in a plain JVM test without Dispatchers.setMain.
    @Test
    fun `constructs and loads projects without a Main test dispatcher`() = runBlocking {
        assertEquals(GkeDiscoveryStep.PICK_PROJECTS, vm.step.value)
        val loaded = withTimeout(5_000) { vm.projectLoadState.first { it is ProjectLoadState.Loaded } }
        assertEquals(
            listOf("example-project", "seed-project"),
            (loaded as ProjectLoadState.Loaded).projects.map { it.projectId },
        )
        // The remembered selection comes from the gateway, filtered to available projects.
        val selection = withTimeout(5_000) { vm.selectedProjects.first { it == setOf("seed-project") } }
        assertEquals(setOf("seed-project"), selection)
    }

    @Test
    fun `import with no cancel imports every cluster and lands on DONE`() = runBlocking {
        driveToPickClusters()
        vm.startImport()
        awaitStep(GkeDiscoveryStep.DONE)
        assertEquals(listOf("cluster-a", "cluster-b", "cluster-c"), fake.importCalls.map { it.third })
        assertTrue(vm.importRows.value.all { it.state is GkeImportRowState.Done })
        assertFalse(vm.busy.value)
        assertTrue(vm.anyImportSucceeded)
    }

    @Test
    fun `cancel mid-import completes the in-flight cluster and cancels the rest`() = runBlocking {
        val gateA = fake.gate("cluster-a")
        val gateB = fake.gate("cluster-b")
        driveToPickClusters()
        vm.startImport()

        // Let cluster 1 finish.
        withTimeout(5_000) { fake.awaitImportStarted("cluster-a") }
        gateA.complete(Result.success("gke_example-project_us-central1_cluster-a"))

        // Cancel while cluster 2 is in flight, then let it complete.
        withTimeout(5_000) { fake.awaitImportStarted("cluster-b") }
        vm.requestCancelImport()
        gateB.complete(Result.success("gke_example-project_us-central1_cluster-b"))

        awaitStep(GkeDiscoveryStep.DONE)
        val rows = rowStates()
        assertTrue(rows["cluster-a"] is GkeImportRowState.Done)
        assertTrue(rows["cluster-b"] is GkeImportRowState.Done)
        assertEquals(GkeImportRowState.Cancelled, rows["cluster-c"])
        // Cluster 3 was never attempted.
        assertEquals(listOf("cluster-a", "cluster-b"), fake.importCalls.map { it.third })
    }

    @Test
    fun `NonCancellable shields the in-flight cluster after cancel`() = runBlocking {
        val gateA = fake.gate("cluster-a")
        driveToPickClusters()
        vm.startImport()

        withTimeout(5_000) { fake.awaitImportStarted("cluster-a") }
        vm.requestCancelImport()
        // The import job is already cancelled, but the in-flight call must
        // still be allowed to return and record its result.
        gateA.complete(Result.success("gke_example-project_us-central1_cluster-a"))

        awaitStep(GkeDiscoveryStep.DONE)
        val rows = rowStates()
        assertEquals(GkeImportRowState.Done("gke_example-project_us-central1_cluster-a"), rows["cluster-a"])
        assertEquals(GkeImportRowState.Cancelled, rows["cluster-b"])
        assertEquals(GkeImportRowState.Cancelled, rows["cluster-c"])
        assertEquals(listOf("cluster-a"), fake.importCalls.map { it.third })
    }

    @Test
    fun `a failing cluster does not abort the remaining imports`() = runBlocking {
        fake.gate("cluster-b").complete(Result.failure(IllegalStateException("boom")))
        driveToPickClusters()
        vm.startImport()
        awaitStep(GkeDiscoveryStep.DONE)
        val rows = rowStates()
        assertTrue(rows["cluster-a"] is GkeImportRowState.Done)
        assertEquals(GkeImportRowState.Failed("boom"), rows["cluster-b"])
        assertTrue(rows["cluster-c"] is GkeImportRowState.Done)
        assertEquals(listOf("cluster-a", "cluster-b", "cluster-c"), fake.importCalls.map { it.third })
    }

    @Test
    fun `stale run completing after reset does not touch the new wizard state`() = runBlocking {
        val gateA = fake.gate("cluster-a")
        driveToPickClusters()
        vm.startImport()
        withTimeout(5_000) { fake.awaitImportStarted("cluster-a") }

        vm.reset()
        assertEquals(GkeDiscoveryStep.PICK_PROJECTS, vm.step.value)

        // Complete the stale run's in-flight import, then join the stale work
        // through the public viewModelScope so the assertion is not vacuous.
        // Only the CANCELLED children are joined: viewModelScope also owns the
        // `projectSelectionExceedsCap` stateIn sharing coroutine, which never
        // completes, so joining every child unconditionally would hang
        // (verified empirically). reset() cancelled the stale import job, so
        // it is exactly the cancelled child; join waits for it to actually
        // finish its (guarded, write-free) completion path.
        gateA.complete(Result.success("gke_example-project_us-central1_cluster-a"))
        withTimeout(5_000) {
            vm.viewModelScope.coroutineContext.job.children
                .filter { it.isCancelled }
                .forEach { it.join() }
        }

        assertEquals(GkeDiscoveryStep.PICK_PROJECTS, vm.step.value)
        assertTrue(vm.importRows.value.isEmpty())
        assertFalse(vm.busy.value)
    }

    @Test
    fun `null backup aborts the import before any cluster is touched`() = runBlocking {
        fake.backupResult = { null }
        driveToPickClusters()
        vm.startImport()
        awaitStep(GkeDiscoveryStep.PICK_CLUSTERS)
        assertTrue(fake.importCalls.isEmpty())
        val error = vm.errorMessage.value
        assertNotNull(error)
        assertTrue(kubeconfigFile.absolutePath in error)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `importCluster and backup receive the gateway kubeconfig path`() = runBlocking {
        driveToPickClusters()
        vm.startImport()
        awaitStep(GkeDiscoveryStep.DONE)
        assertEquals(listOf(kubeconfigFile.absolutePath), fake.backupRequests.toList())
        assertEquals(3, fake.importPaths.size)
        assertTrue(fake.importPaths.all { it == kubeconfigFile.absolutePath })
        // The scan persisted the selection through the gateway, not the real DataStore.
        assertEquals(listOf(listOf("example-project", "seed-project")), fake.rememberedSelections.toList())
    }
}

/**
 * In-memory [GkeDiscoveryGateway]. `importCluster` records its calls in order
 * and, when the test installed a [gate] for the cluster, suspends until the
 * test completes it — letting tests cancel while an import is in flight.
 * Without a gate it returns success immediately.
 */
private class FakeGkeDiscoveryGateway(
    private val kubeconfig: File,
) : GkeDiscoveryGateway {

    override val gcloudAvailable: Boolean = true
    override val authPluginAvailable: Boolean = true

    var projects: List<GcpProject> = listOf(
        GcpProject("example-project", "Example Project"),
        GcpProject("seed-project", "Seed Project"),
    )
    var clusters: Map<String, List<GkeCluster>> = emptyMap()
    var backupResult: (String) -> File? = { File("$it.backup") }

    val importCalls: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
    val importPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val backupRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val rememberedSelections: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    private val gates = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Install (or fetch) the gate `importCluster` will await for [clusterName]. */
    fun gate(clusterName: String): CompletableDeferred<Result<String>> = gates.getOrPut(clusterName) { CompletableDeferred() }

    /** Suspends until `importCluster` has been entered for [clusterName]. */
    suspend fun awaitImportStarted(clusterName: String) {
        started.getOrPut(clusterName) { CompletableDeferred() }.await()
    }

    override suspend fun activeAccount(): Result<String?> = Result.success("dev@example.com")

    override suspend fun listProjects(): Result<List<GcpProject>> = Result.success(projects)

    override suspend fun listClusters(projectId: String): Result<List<GkeCluster>> = Result.success(clusters[projectId].orEmpty())

    override suspend fun importCluster(
        projectId: String,
        location: String,
        clusterName: String,
        kubeconfigPath: String,
    ): Result<String> {
        importCalls.add(Triple(projectId, location, clusterName))
        importPaths.add(kubeconfigPath)
        started.getOrPut(clusterName) { CompletableDeferred() }.complete(Unit)
        val gate = gates[clusterName]
        return gate?.await() ?: Result.success("gke_${projectId}_${location}_$clusterName")
    }

    override fun parseContext(ctx: String): Triple<String, String, String>? = null

    override fun kubeconfigPath(): String = kubeconfig.absolutePath

    override fun backupKubeconfig(kubeconfigPath: String): File? {
        backupRequests.add(kubeconfigPath)
        return backupResult(kubeconfigPath)
    }

    override fun existingContexts(): List<String> = emptyList()

    override fun recallProjectSelection(): List<String> = listOf("seed-project", "not-a-real-project")

    override fun rememberProjectSelection(projectIds: List<String>) {
        rememberedSelections.add(projectIds)
    }
}
