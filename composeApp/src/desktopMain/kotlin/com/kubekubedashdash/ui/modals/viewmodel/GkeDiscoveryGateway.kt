package com.kubekubedashdash.ui.modals.viewmodel

import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.util.EksClusterDiscoverer
import com.kubekubedashdash.util.GcpProject
import com.kubekubedashdash.util.GkeCluster
import com.kubekubedashdash.util.GkeClusterDiscoverer
import com.kubekubedashdash.util.KubeconfigLocator
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.ShellEnvironment
import java.io.File

/**
 * Seam between [GkeDiscoveryViewModel] and everything that touches the outside
 * world: `gcloud` subprocesses, the kubeconfig on disk, the kube contexts, and
 * the preferences DataStore. Production uses [DefaultGkeDiscoveryGateway];
 * tests substitute a fake so the wizard's scan/import/cancellation logic can
 * run without shelling out or reading/writing the developer's real files.
 */
interface GkeDiscoveryGateway {
    val gcloudAvailable: Boolean

    val authPluginAvailable: Boolean

    suspend fun activeAccount(): Result<String?>

    suspend fun listProjects(): Result<List<GcpProject>>

    suspend fun listClusters(projectId: String): Result<List<GkeCluster>>

    suspend fun importCluster(
        projectId: String,
        location: String,
        clusterName: String,
        kubeconfigPath: String,
    ): Result<String>

    fun parseContext(ctx: String): Triple<String, String, String>?

    fun kubeconfigPath(): String

    fun backupKubeconfig(kubeconfigPath: String): File?

    fun existingContexts(): List<String>

    fun recallProjectSelection(): List<String>

    fun rememberProjectSelection(projectIds: List<String>)
}

/** Delegates every member to the pre-existing singletons — no behavior change. */
class DefaultGkeDiscoveryGateway(
    private val client: ReactiveKubeClient,
) : GkeDiscoveryGateway {
    override val gcloudAvailable: Boolean
        get() = GkeClusterDiscoverer.isGcloudAvailable()

    override val authPluginAvailable: Boolean
        get() = ShellEnvironment.resolveCommand("gke-gcloud-auth-plugin") != null

    override suspend fun activeAccount(): Result<String?> = GkeClusterDiscoverer.activeAccount()

    override suspend fun listProjects(): Result<List<GcpProject>> = GkeClusterDiscoverer.listProjects()

    override suspend fun listClusters(projectId: String): Result<List<GkeCluster>> = GkeClusterDiscoverer.listClusters(projectId)

    override suspend fun importCluster(
        projectId: String,
        location: String,
        clusterName: String,
        kubeconfigPath: String,
    ): Result<String> = GkeClusterDiscoverer.importCluster(projectId, location, clusterName, kubeconfigPath)

    override fun parseContext(ctx: String): Triple<String, String, String>? = GkeClusterDiscoverer.parseGkeContext(ctx)

    override fun kubeconfigPath(): String = KubeconfigLocator.activePath()

    override fun backupKubeconfig(kubeconfigPath: String): File? = EksClusterDiscoverer.backupKubeconfig(kubeconfigPath)

    override fun existingContexts(): List<String> = client.getContexts()

    override fun recallProjectSelection(): List<String> = PreferenceRepository.lastGcpProjects.value

    override fun rememberProjectSelection(projectIds: List<String>) = PreferenceRepository.setLastGcpProjects(projectIds)
}
