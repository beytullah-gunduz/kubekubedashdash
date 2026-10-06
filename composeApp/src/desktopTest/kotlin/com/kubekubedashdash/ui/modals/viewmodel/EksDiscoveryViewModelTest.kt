package com.kubekubedashdash.ui.modals.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.util.AwsProfile
import com.kubekubedashdash.util.EksCluster
import com.kubekubedashdash.util.shutdownCleanly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [EksDiscoveryViewModel] through the real wizard flow (profiles →
 * regions → scan → pick → import) against [FakeEksDiscoveryGateway], covering
 * the cancellation and generation-guard behavior of `8c94f55` that was
 * previously only verifiable by hand.
 *
 * No `Dispatchers.setMain` here (plan D3): `kotlinx-coroutines-swing` is on
 * the classpath, so `viewModelScope`'s Main dispatcher resolves to the Swing
 * dispatcher and works in a plain JVM test. Tests sequence real concurrency
 * with [CompletableDeferred] gates and bounded [withTimeout] awaits — never
 * `Thread.sleep`.
 *
 * The fake gateway also absorbs the preference persistence: these tests must
 * not initialize the real settings DataStore (which would overwrite the
 * developer's saved profile selection) nor touch the real kubeconfig.
 */
class EksDiscoveryViewModelTest {

    private val clusterA = EksCluster("cluster-a", "us-east-1", "example-profile")
    private val clusterB = EksCluster("cluster-b", "us-east-1", "example-profile")
    private val clusterC = EksCluster("cluster-c", "us-east-1", "example-profile")

    private lateinit var kubeconfigFile: File
    private lateinit var fake: FakeEksDiscoveryGateway
    private lateinit var vm: EksDiscoveryViewModel

    @BeforeTest
    fun setUp() {
        kubeconfigFile = File.createTempFile("eks-discovery-test-kubeconfig", ".yaml").apply {
            writeText("apiVersion: v1\nkind: Config\n")
            deleteOnExit()
        }
        fake = FakeEksDiscoveryGateway(kubeconfigFile)
        fake.clusters = mapOf(("example-profile" to "us-east-1") to listOf(clusterA, clusterB, clusterC))
        vm = EksDiscoveryViewModel(fake)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(vm.viewModelScope, label = "EksDiscoveryViewModelTest")
        kubeconfigFile.delete()
    }

    /**
     * Walks the wizard to PICK_CLUSTERS the way the UI does. Mandatory
     * sequencing (plan WS1 step 6, mirrored for EKS): construction launches
     * `loadProfiles()` on Dispatchers.IO which asynchronously assigns the
     * remembered selection, so a test must await both the profile list AND the
     * recalled selection before mutating the selection — otherwise the
     * late-arriving load can overwrite the test's toggle. The recalled
     * `seed-profile` selection is the observable signal that the load's
     * selection write has landed. After `proceedFromProfile()` the helper also
     * awaits the (asynchronously launched) selection persistence, so tests can
     * assert on it deterministically.
     */
    private suspend fun driveToPickClusters() {
        withTimeout(5_000) { vm.profiles.first { it.isNotEmpty() } }
        withTimeout(5_000) { vm.selectedProfiles.first { "seed-profile" in it } }
        vm.toggleProfile("example-profile")
        vm.proceedFromProfile()
        withTimeout(5_000) { vm.step.first { it == EksDiscoveryStep.PICK_REGIONS } }
        withTimeout(5_000) { fake.awaitSelectionRemembered() }
        vm.startDiscovery()
        withTimeout(5_000) { vm.step.first { it == EksDiscoveryStep.PICK_CLUSTERS } }
    }

    private suspend fun awaitStep(step: EksDiscoveryStep) {
        withTimeout(5_000) { vm.step.first { it == step } }
    }

    private fun rowStates(): Map<String, ImportRowState> = vm.importRows.value.associate { it.cluster.name to it.state }

    /**
     * `setUp()` builds [vm] before each test body runs, so its `init` has already loaded profiles
     * and read contexts from the default fake. Tests that need a differently configured fake
     * rebuild both; counters and lists are then asserted on the rebuilt fake only.
     */
    private fun rebuild(configure: FakeEksDiscoveryGateway.() -> Unit) {
        shutdownCleanly(vm.viewModelScope, label = "EksDiscoveryViewModelTest")
        fake = FakeEksDiscoveryGateway(kubeconfigFile).apply {
            clusters = mapOf(("example-profile" to "us-east-1") to listOf(clusterA, clusterB, clusterC))
            configure()
        }
        vm = EksDiscoveryViewModel(fake)
    }

    /** Waits until `loadProfiles()` has landed: the by-name profile is set after the profile list. */
    private suspend fun awaitProfilesLoaded() {
        withTimeout(5_000) { vm.byNameProfile.first { it.isNotEmpty() } }
    }

    private fun fillValidByNameFields(region: String = "us-east-1") {
        vm.setByNameProfile("example-profile")
        vm.setByNameRegion(region)
        vm.setByNameCluster("example-cluster")
    }

    // Probe mirroring WS1 step 1: proves the ViewModel constructs and its
    // init-time load completes in a plain JVM test without Dispatchers.setMain.
    @Test
    fun `constructs and loads profiles without a Main test dispatcher`() = runBlocking {
        assertEquals(EksDiscoveryStep.PICK_PROFILE, vm.step.value)
        val profiles = withTimeout(5_000) { vm.profiles.first { it.isNotEmpty() } }
        assertEquals(listOf("example-profile", "seed-profile"), profiles.map { it.name })
        // The remembered selection comes from the gateway, filtered to available profiles.
        val selection = withTimeout(5_000) { vm.selectedProfiles.first { it == setOf("seed-profile") } }
        assertEquals(setOf("seed-profile"), selection)
    }

    @Test
    fun `import with no cancel imports every cluster and lands on DONE`() = runBlocking {
        driveToPickClusters()
        vm.startImport()
        awaitStep(EksDiscoveryStep.DONE)
        assertEquals(listOf("cluster-a", "cluster-b", "cluster-c"), fake.importCalls.map { it.third })
        assertTrue(vm.importRows.value.all { it.state is ImportRowState.Done })
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
        gateA.complete(Result.success("arn:aws:eks:us-east-1:000000000000:cluster/cluster-a"))

        // Cancel while cluster 2 is in flight, then let it complete.
        withTimeout(5_000) { fake.awaitImportStarted("cluster-b") }
        vm.requestCancelImport()
        gateB.complete(Result.success("arn:aws:eks:us-east-1:000000000000:cluster/cluster-b"))

        awaitStep(EksDiscoveryStep.DONE)
        val rows = rowStates()
        assertTrue(rows["cluster-a"] is ImportRowState.Done)
        assertTrue(rows["cluster-b"] is ImportRowState.Done)
        assertEquals(ImportRowState.Cancelled, rows["cluster-c"])
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
        gateA.complete(Result.success("arn:aws:eks:us-east-1:000000000000:cluster/cluster-a"))

        awaitStep(EksDiscoveryStep.DONE)
        val rows = rowStates()
        assertEquals(ImportRowState.Done("arn:aws:eks:us-east-1:000000000000:cluster/cluster-a"), rows["cluster-a"])
        assertEquals(ImportRowState.Cancelled, rows["cluster-b"])
        assertEquals(ImportRowState.Cancelled, rows["cluster-c"])
        assertEquals(listOf("cluster-a"), fake.importCalls.map { it.third })
    }

    @Test
    fun `a failing cluster does not abort the remaining imports`() = runBlocking {
        fake.gate("cluster-b").complete(Result.failure(IllegalStateException("boom")))
        driveToPickClusters()
        vm.startImport()
        awaitStep(EksDiscoveryStep.DONE)
        val rows = rowStates()
        assertTrue(rows["cluster-a"] is ImportRowState.Done)
        assertEquals(ImportRowState.Failed("boom"), rows["cluster-b"])
        assertTrue(rows["cluster-c"] is ImportRowState.Done)
        assertEquals(listOf("cluster-a", "cluster-b", "cluster-c"), fake.importCalls.map { it.third })
    }

    @Test
    fun `stale run completing after reset does not touch the new wizard state`() = runBlocking {
        val gateA = fake.gate("cluster-a")
        driveToPickClusters()
        vm.startImport()
        withTimeout(5_000) { fake.awaitImportStarted("cluster-a") }

        vm.reset()
        assertEquals(EksDiscoveryStep.PICK_PROFILE, vm.step.value)

        // Complete the stale run's in-flight import, then join the stale work
        // through the public viewModelScope so the assertion is not vacuous.
        // Only the CANCELLED children are joined (WS1 lesson: joining every
        // child unconditionally hangs if the scope ever owns a never-completing
        // sharing coroutine). reset() cancelled the stale import job, so it is
        // exactly the cancelled child; join waits for it to actually finish its
        // (guarded, write-free) completion path.
        gateA.complete(Result.success("arn:aws:eks:us-east-1:000000000000:cluster/cluster-a"))
        withTimeout(5_000) {
            vm.viewModelScope.coroutineContext.job.children
                .filter { it.isCancelled }
                .forEach { it.join() }
        }

        assertEquals(EksDiscoveryStep.PICK_PROFILE, vm.step.value)
        assertTrue(vm.importRows.value.isEmpty())
        assertFalse(vm.busy.value)
    }

    // Pins the deliberate asymmetry with GKE: the EKS backup is best-effort
    // (`aws eks update-kubeconfig` merges rather than rewrites the kubeconfig),
    // so a failed backup must NOT abort the import. Do not "align" this with
    // GKE's mandatory-backup abort.
    @Test
    fun `null backup does not abort the import`() = runBlocking {
        fake.backupResult = { null }
        driveToPickClusters()
        vm.startImport()
        awaitStep(EksDiscoveryStep.DONE)
        assertEquals(listOf(kubeconfigFile.absolutePath), fake.backupRequests.toList())
        assertEquals(listOf("cluster-a", "cluster-b", "cluster-c"), fake.importCalls.map { it.third })
        assertTrue(vm.importRows.value.all { it.state is ImportRowState.Done })
        assertNull(vm.errorMessage.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `importCluster and backup receive the gateway kubeconfig path`() = runBlocking {
        driveToPickClusters()
        vm.startImport()
        awaitStep(EksDiscoveryStep.DONE)
        assertEquals(listOf(kubeconfigFile.absolutePath), fake.backupRequests.toList())
        assertEquals(3, fake.importPaths.size)
        assertTrue(fake.importPaths.all { it == kubeconfigFile.absolutePath })
        // proceedFromProfile persisted the selection through the gateway, not the real DataStore.
        assertEquals(listOf(listOf("example-profile", "seed-profile")), fake.rememberedSelections.toList())
    }

    // ── "Enter by name" tab ────────────────────────────────────────────────

    @Test
    fun `by-name profile defaults to the remembered profile`() = runBlocking {
        val profile = withTimeout(5_000) { vm.byNameProfile.first { it.isNotEmpty() } }
        assertEquals("seed-profile", profile)
    }

    @Test
    fun `paste of an ARN fills region and cluster, an unknown pasted profile is not selected`() = runBlocking {
        awaitProfilesLoaded()
        vm.onPasteTextChange("arn:aws-us-gov:eks:us-gov-west-1:000000000000:cluster/example-cluster")
        assertEquals("us-gov-west-1", vm.byNameRegion.value)
        assertEquals("example-cluster", vm.byNameCluster.value)
        assertEquals(PasteNotice("Filled the region and cluster.", recognized = true), vm.pasteNotice.value)

        vm.onPasteTextChange("aws eks update-kubeconfig --name example-cluster --region us-east-1 --profile ghost-profile")
        assertEquals("seed-profile", vm.byNameProfile.value)
        assertEquals("us-east-1", vm.byNameRegion.value)
        val notice = vm.pasteNotice.value
        assertNotNull(notice)
        assertTrue(notice.recognized)
        assertTrue(notice.text.endsWith("isn't in your AWS config, so it was not selected."))

        vm.onPasteTextChange("hello")
        assertEquals(PasteNotice(EKS_PASTE_NOT_RECOGNIZED, recognized = false), vm.pasteNotice.value)
        vm.onPasteTextChange("")
        assertNull(vm.pasteNotice.value)
    }

    @Test
    fun `GovCloud region can be imported by name`() = runBlocking {
        awaitProfilesLoaded()
        fillValidByNameFields(region = "us-gov-west-1")
        withTimeout(5_000) { vm.byNameState.first { it.canImport } }
        vm.startByNameImport()
        awaitStep(EksDiscoveryStep.DONE)
        assertEquals(listOf(Triple("example-profile", "us-gov-west-1", "example-cluster")), fake.importCalls.toList())
        assertTrue(vm.anyImportSucceeded)
    }

    @Test
    fun `region suggestions put the profile default first`() = runBlocking {
        rebuild {
            contexts = listOf("arn:aws:eks:eu-west-1:000000000000:cluster/example-cluster")
            profiles = listOf(
                AwsProfile("example-profile", "us-east-1", AwsProfile.Source.BOTH),
                AwsProfile("other-profile", "ap-south-1", AwsProfile.Source.CONFIG),
            )
        }
        awaitProfilesLoaded()
        vm.setByNameProfile("example-profile")
        val first = withTimeout(5_000) {
            vm.byNameRegionSuggestions.first { it.firstOrNull() == "us-east-1" && "eu-west-1" in it }
        }
        assertEquals(listOf("us-east-1", "eu-west-1", "us-west-2"), first)

        vm.setByNameProfile("other-profile")
        val second = withTimeout(5_000) { vm.byNameRegionSuggestions.first { it.firstOrNull() == "ap-south-1" } }
        assertEquals(listOf("ap-south-1", "eu-west-1", "us-east-1", "us-west-2"), second)
        assertTrue(second.size <= 8)
    }

    @Test
    fun `add another keeps profile and region and the sticky import flag`() = runBlocking {
        awaitProfilesLoaded()
        fillValidByNameFields()
        vm.startByNameImport()
        awaitStep(EksDiscoveryStep.DONE)

        vm.addAnotherByName()
        assertEquals(EksDiscoveryStep.PICK_PROFILE, vm.step.value)
        assertEquals("", vm.byNameCluster.value)
        assertEquals("example-profile", vm.byNameProfile.value)
        assertEquals("us-east-1", vm.byNameRegion.value)
        assertTrue(vm.anyImportSucceeded)

        vm.cancel()
        assertTrue(vm.anyImportSucceeded)

        vm.reset()
        assertFalse(vm.anyImportSucceeded)
        assertEquals("", vm.byNameRegion.value)
        assertEquals("", vm.byNameCluster.value)
        // reset() keeps the profile.
        assertEquals("example-profile", vm.byNameProfile.value)
    }

    @Test
    fun `an access-denied import shows the profile hint`() = runBlocking {
        fake.gate("example-cluster").complete(
            Result.failure(
                RuntimeException(
                    "An error occurred (AccessDeniedException) when calling the DescribeCluster operation: " +
                        "User: example is not authorized to perform: eks:DescribeCluster",
                ),
            ),
        )
        awaitProfilesLoaded()
        fillValidByNameFields()
        vm.startByNameImport()
        awaitStep(EksDiscoveryStep.DONE)
        val state = rowStates()["example-cluster"]
        assertTrue(state is ImportRowState.Failed)
        assertTrue(state.message.startsWith("This profile isn't allowed to read the cluster"))
        assertTrue(state.message.contains("AccessDeniedException"))
    }

    @Test
    fun `a GovCloud context counts as already imported in the browse flow`() = runBlocking {
        rebuild {
            contexts = listOf("arn:aws-us-gov:eks:us-gov-west-1:000000000000:cluster/cluster-a")
            profiles = listOf(AwsProfile("example-profile", "us-gov-west-1", AwsProfile.Source.BOTH))
            clusters = mapOf(
                ("example-profile" to "us-gov-west-1") to listOf(EksCluster("cluster-a", "us-gov-west-1", "example-profile")),
            )
        }
        withTimeout(5_000) { vm.selectedProfiles.first { it == setOf("example-profile") } }
        vm.proceedFromProfile()
        vm.startDiscovery()
        awaitStep(EksDiscoveryStep.PICK_CLUSTERS)
        val candidate = vm.candidates.value.single()
        assertEquals("cluster-a", candidate.cluster.name)
        assertTrue(candidate.alreadyImported)
        assertFalse(candidate.selected)
    }
}
