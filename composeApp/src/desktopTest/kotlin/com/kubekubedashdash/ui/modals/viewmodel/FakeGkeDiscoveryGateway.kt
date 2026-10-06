package com.kubekubedashdash.ui.modals.viewmodel

import com.kubekubedashdash.util.GcpProject
import com.kubekubedashdash.util.GkeCluster
import com.kubekubedashdash.util.GkeClusterDiscoverer
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [GkeDiscoveryGateway]. `importCluster` records its calls in order
 * and, when the test installed a [gate] for the cluster, suspends until the
 * test completes it — letting tests cancel while an import is in flight.
 * Without a gate it returns success immediately.
 */
internal class FakeGkeDiscoveryGateway(
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

    /** What [recallDiscoveryMode] returns: the tab the modal "remembered" from the last run. */
    var initialMode: DiscoveryMode = DiscoveryMode.BROWSE

    /** What [existingContexts] returns: the contexts already in the pretend kubeconfig. */
    var contexts: List<String> = emptyList()

    val importCalls: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
    val importPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val backupRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val rememberedSelections: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val rememberedModes: MutableList<DiscoveryMode> = Collections.synchronizedList(mutableListOf())
    val listProjectsCalls = AtomicInteger(0)

    private val gates = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Install (or fetch) the gate `importCluster` will await for [clusterName]. */
    fun gate(clusterName: String): CompletableDeferred<Result<String>> = gates.getOrPut(clusterName) { CompletableDeferred() }

    /** Suspends until `importCluster` has been entered for [clusterName]. */
    suspend fun awaitImportStarted(clusterName: String) {
        started.getOrPut(clusterName) { CompletableDeferred() }.await()
    }

    /** What `gcloud auth list` answers; a failure stands for a broken gcloud. */
    var accountResult: Result<String?> = Result.success("dev@example.com")

    /** When set, `gcloud projects list` fails with it. */
    var projectsFailure: Throwable? = null

    /** Projects whose `clusters list` is refused, with the error gcloud would give. */
    var clusterFailures: Map<String, Throwable> = emptyMap()

    override suspend fun activeAccount(): Result<String?> = accountResult

    override suspend fun listProjects(): Result<List<GcpProject>> {
        listProjectsCalls.incrementAndGet()
        return projectsFailure?.let { Result.failure(it) } ?: Result.success(projects)
    }

    override suspend fun listClusters(projectId: String): Result<List<GkeCluster>> = clusterFailures[projectId]?.let { Result.failure(it) } ?: Result.success(clusters[projectId].orEmpty())

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

    override fun parseContext(ctx: String): Triple<String, String, String>? = GkeClusterDiscoverer.parseGkeContext(ctx)

    override fun kubeconfigPath(): String = kubeconfig.absolutePath

    override fun backupKubeconfig(kubeconfigPath: String): File? {
        backupRequests.add(kubeconfigPath)
        return backupResult(kubeconfigPath)
    }

    override fun existingContexts(): List<String> = contexts

    override fun recallProjectSelection(): List<String> = listOf("seed-project", "not-a-real-project")

    override fun rememberProjectSelection(projectIds: List<String>) {
        rememberedSelections.add(projectIds)
    }

    override fun recallDiscoveryMode(): DiscoveryMode = initialMode

    override fun rememberDiscoveryMode(mode: DiscoveryMode) {
        rememberedModes.add(mode)
    }
}
