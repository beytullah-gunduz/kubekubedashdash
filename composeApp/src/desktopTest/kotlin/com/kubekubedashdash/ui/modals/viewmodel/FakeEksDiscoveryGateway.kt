package com.kubekubedashdash.ui.modals.viewmodel

import com.kubekubedashdash.util.AwsProfile
import com.kubekubedashdash.util.EksCluster
import com.kubekubedashdash.util.EksClusterDiscoverer
import com.kubekubedashdash.util.EksContextRef
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [EksDiscoveryGateway]. `importCluster` records its calls in order
 * and, when the test installed a [gate] for the cluster, suspends until the
 * test completes it — letting tests cancel while an import is in flight.
 * Without a gate it returns success immediately.
 */
internal class FakeEksDiscoveryGateway(
    private val kubeconfig: File,
) : EksDiscoveryGateway {

    override val awsCliAvailable: Boolean = true
    override val commonRegions: List<String> = listOf("us-east-1", "us-west-2")

    var profiles: List<AwsProfile> = listOf(
        AwsProfile("example-profile", "us-east-1", AwsProfile.Source.BOTH),
        AwsProfile("seed-profile", "us-east-1", AwsProfile.Source.CONFIG),
    )
    var clusters: Map<Pair<String, String>, List<EksCluster>> = emptyMap()
    var backupResult: (String) -> File? = { File("$it.backup") }

    /** What [recallDiscoveryMode] returns: the tab the modal "remembered" from the last run. */
    var initialMode: DiscoveryMode = DiscoveryMode.BROWSE

    /** What [existingContexts] returns: the contexts already in the pretend kubeconfig. */
    var contexts: List<String> = emptyList()

    val importCalls: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
    val importPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val backupRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val rememberedSelections: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val rememberedModes: MutableList<DiscoveryMode> = Collections.synchronizedList(mutableListOf())

    private val gates = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val selectionRemembered = CompletableDeferred<Unit>()

    /** Install (or fetch) the gate `importCluster` will await for [clusterName]. */
    fun gate(clusterName: String): CompletableDeferred<Result<String>> = gates.getOrPut(clusterName) { CompletableDeferred() }

    /** Suspends until `importCluster` has been entered for [clusterName]. */
    suspend fun awaitImportStarted(clusterName: String) {
        started.getOrPut(clusterName) { CompletableDeferred() }.await()
    }

    /** Suspends until `rememberProfileSelection` has been called at least once. */
    suspend fun awaitSelectionRemembered() = selectionRemembered.await()

    override fun listProfiles(): List<AwsProfile> = profiles

    override suspend fun listEnabledRegions(profile: String): Result<List<String>> = Result.success(listOf("us-east-1"))

    override suspend fun listClusters(
        profile: String,
        region: String,
    ): Result<List<EksCluster>> = Result.success(clusters[profile to region].orEmpty())

    override suspend fun importCluster(
        profile: String,
        region: String,
        clusterName: String,
        kubeconfigPath: String,
    ): Result<String> {
        importCalls.add(Triple(profile, region, clusterName))
        importPaths.add(kubeconfigPath)
        started.getOrPut(clusterName) { CompletableDeferred() }.complete(Unit)
        val gate = gates[clusterName]
        return gate?.await() ?: Result.success("arn:aws:eks:$region:000000000000:cluster/$clusterName")
    }

    override fun kubeconfigPath(): String = kubeconfig.absolutePath

    override fun backupKubeconfig(kubeconfigPath: String): File? {
        backupRequests.add(kubeconfigPath)
        return backupResult(kubeconfigPath)
    }

    override fun existingContexts(): List<String> = contexts

    override fun recallProfileSelection(): List<String> = listOf("seed-profile", "not-a-real-profile")

    override fun rememberProfileSelection(profileNames: List<String>) {
        rememberedSelections.add(profileNames)
        selectionRemembered.complete(Unit)
    }

    override fun recallDiscoveryMode(): DiscoveryMode = initialMode

    override fun rememberDiscoveryMode(mode: DiscoveryMode) {
        rememberedModes.add(mode)
    }

    override fun parseContext(ctx: String): EksContextRef? = EksClusterDiscoverer.parseEksContext(ctx)
}
