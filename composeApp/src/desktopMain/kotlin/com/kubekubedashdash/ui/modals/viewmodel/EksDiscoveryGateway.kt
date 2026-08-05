package com.kubekubedashdash.ui.modals.viewmodel

import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.util.AwsProfile
import com.kubekubedashdash.util.AwsProfileReader
import com.kubekubedashdash.util.EksCluster
import com.kubekubedashdash.util.EksClusterDiscoverer
import com.kubekubedashdash.util.KubeconfigLocator
import com.kubekubedashdash.util.ReactiveKubeClient
import java.io.File

/**
 * Seam between [EksDiscoveryViewModel] and everything that touches the outside
 * world: `aws` subprocesses, the AWS profile files, the kubeconfig on disk, the
 * kube contexts, and the preferences DataStore. Production uses
 * [DefaultEksDiscoveryGateway]; tests substitute a fake so the wizard's
 * scan/import/cancellation logic can run without shelling out or
 * reading/writing the developer's real files.
 */
interface EksDiscoveryGateway {
    val awsCliAvailable: Boolean

    val commonRegions: List<String>

    fun listProfiles(): List<AwsProfile>

    suspend fun listEnabledRegions(profile: String): Result<List<String>>

    suspend fun listClusters(
        profile: String,
        region: String,
    ): Result<List<EksCluster>>

    suspend fun importCluster(
        profile: String,
        region: String,
        clusterName: String,
        kubeconfigPath: String,
    ): Result<String>

    fun kubeconfigPath(): String

    fun backupKubeconfig(kubeconfigPath: String): File?

    fun existingContexts(): List<String>

    fun recallProfileSelection(): List<String>

    fun rememberProfileSelection(profileNames: List<String>)
}

/** Delegates every member to the pre-existing singletons — no behavior change. */
class DefaultEksDiscoveryGateway(
    private val client: ReactiveKubeClient,
) : EksDiscoveryGateway {
    override val awsCliAvailable: Boolean
        get() = EksClusterDiscoverer.isAwsCliAvailable()

    override val commonRegions: List<String>
        get() = EksClusterDiscoverer.COMMON_REGIONS

    override fun listProfiles(): List<AwsProfile> = AwsProfileReader.listProfiles()

    override suspend fun listEnabledRegions(profile: String): Result<List<String>> = EksClusterDiscoverer.listEnabledRegions(profile)

    override suspend fun listClusters(
        profile: String,
        region: String,
    ): Result<List<EksCluster>> = EksClusterDiscoverer.listClusters(profile, region)

    override suspend fun importCluster(
        profile: String,
        region: String,
        clusterName: String,
        kubeconfigPath: String,
    ): Result<String> = EksClusterDiscoverer.importCluster(profile, region, clusterName, kubeconfigPath)

    override fun kubeconfigPath(): String = KubeconfigLocator.activePath()

    override fun backupKubeconfig(kubeconfigPath: String): File? = EksClusterDiscoverer.backupKubeconfig(kubeconfigPath)

    override fun existingContexts(): List<String> = client.getContexts()

    override fun recallProfileSelection(): List<String> = PreferenceRepository.lastAwsProfiles.value

    override fun rememberProfileSelection(profileNames: List<String>) = PreferenceRepository.setLastAwsProfiles(profileNames)
}
