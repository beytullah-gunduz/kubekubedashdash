package com.kubekubedashdash.ui.modals.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.services.KubeClientService
import com.kubekubedashdash.util.AwsProfile
import com.kubekubedashdash.util.AwsProfileReader
import com.kubekubedashdash.util.EksCluster
import com.kubekubedashdash.util.EksClusterDiscoverer
import com.kubekubedashdash.util.KubeconfigLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

enum class EksDiscoveryStep {
    PICK_PROFILE,
    PICK_REGIONS,
    SCANNING,
    PICK_CLUSTERS,
    IMPORTING,
    DONE,
}

enum class RegionScope { DEFAULT_ONLY, COMMON, ALL_ENABLED }

sealed class RegionScanState {
    object Pending : RegionScanState()
    object Scanning : RegionScanState()
    data class Done(val clusters: List<EksCluster>) : RegionScanState()
    data class Failed(val message: String) : RegionScanState()
}

data class RegionScanRow(val region: String, val state: RegionScanState)

data class ClusterCandidate(
    val cluster: EksCluster,
    val alreadyImported: Boolean,
    val selected: Boolean,
)

sealed class ImportRowState {
    object Pending : ImportRowState()
    object Importing : ImportRowState()
    data class Done(val contextName: String) : ImportRowState()
    data class Failed(val message: String) : ImportRowState()
}

data class ImportRow(val cluster: EksCluster, val state: ImportRowState)

class EksDiscoveryViewModel : ViewModel() {

    private val log = LoggerFactory.getLogger(EksDiscoveryViewModel::class.java)
    private val reactiveClient = KubeClientService.reactiveClient

    private val _step = MutableStateFlow(EksDiscoveryStep.PICK_PROFILE)
    val step: StateFlow<EksDiscoveryStep> = _step.asStateFlow()

    private val _profiles = MutableStateFlow<List<AwsProfile>>(emptyList())
    val profiles: StateFlow<List<AwsProfile>> = _profiles.asStateFlow()

    private val _selectedProfile = MutableStateFlow<String?>(null)
    val selectedProfile: StateFlow<String?> = _selectedProfile.asStateFlow()

    private val _regionScope = MutableStateFlow(RegionScope.DEFAULT_ONLY)
    val regionScope: StateFlow<RegionScope> = _regionScope.asStateFlow()

    private val _scanRows = MutableStateFlow<List<RegionScanRow>>(emptyList())
    val scanRows: StateFlow<List<RegionScanRow>> = _scanRows.asStateFlow()

    private val _candidates = MutableStateFlow<List<ClusterCandidate>>(emptyList())
    val candidates: StateFlow<List<ClusterCandidate>> = _candidates.asStateFlow()

    private val _importRows = MutableStateFlow<List<ImportRow>>(emptyList())
    val importRows: StateFlow<List<ImportRow>> = _importRows.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val awsCliAvailable: Boolean = EksClusterDiscoverer.isAwsCliAvailable()

    private var activeJob: Job? = null

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = AwsProfileReader.listProfiles()
            _profiles.value = list
            val preselect = PreferenceRepository.lastAwsProfile?.takeIf { name ->
                list.any { it.name == name }
            } ?: list.firstOrNull()?.name
            _selectedProfile.value = preselect
        }
    }

    fun selectProfile(name: String) {
        _selectedProfile.value = name
    }

    fun setRegionScope(scope: RegionScope) {
        _regionScope.value = scope
    }

    fun goToStep(step: EksDiscoveryStep) {
        _errorMessage.value = null
        _step.value = step
    }

    fun proceedFromProfile() {
        val profile = _selectedProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            PreferenceRepository.lastAwsProfile = profile
        }
        _step.value = EksDiscoveryStep.PICK_REGIONS
    }

    fun startDiscovery() {
        val profile = _selectedProfile.value ?: return
        activeJob?.cancel()
        _errorMessage.value = null
        _busy.value = true
        _step.value = EksDiscoveryStep.SCANNING
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val regions = resolveRegions(profile, _regionScope.value)
            if (regions.isEmpty()) {
                _errorMessage.value = "No regions to scan."
                _busy.value = false
                _step.value = EksDiscoveryStep.PICK_REGIONS
                return@launch
            }
            _scanRows.value = regions.map { RegionScanRow(it, RegionScanState.Pending) }
            scanRegions(profile, regions)
            buildCandidates()
            _busy.value = false
            _step.value = EksDiscoveryStep.PICK_CLUSTERS
        }
    }

    private suspend fun resolveRegions(profile: String, scope: RegionScope): List<String> {
        val profileDefault = _profiles.value.firstOrNull { it.name == profile }?.defaultRegion
        return when (scope) {
            RegionScope.DEFAULT_ONLY -> listOfNotNull(profileDefault ?: "us-east-1")

            RegionScope.COMMON -> {
                val merged = (listOfNotNull(profileDefault) + EksClusterDiscoverer.COMMON_REGIONS).distinct()
                merged
            }

            RegionScope.ALL_ENABLED -> {
                EksClusterDiscoverer.listEnabledRegions(profile).getOrElse {
                    log.warn("Falling back to common regions: {}", it.message)
                    EksClusterDiscoverer.COMMON_REGIONS
                }
            }
        }
    }

    private suspend fun scanRegions(profile: String, regions: List<String>) = coroutineScope {
        val semaphore = Semaphore(permits = 6)
        regions.map { region ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    updateScanRow(region) { RegionScanState.Scanning }
                    val result = EksClusterDiscoverer.listClusters(profile, region)
                    result.fold(
                        onSuccess = { clusters ->
                            updateScanRow(region) { RegionScanState.Done(clusters) }
                        },
                        onFailure = { e ->
                            val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                            updateScanRow(region) { RegionScanState.Failed(msg) }
                        },
                    )
                }
            }
        }.awaitAll()
    }

    private fun updateScanRow(region: String, transform: (RegionScanState) -> RegionScanState) {
        _scanRows.update { rows ->
            rows.map { row -> if (row.region == region) row.copy(state = transform(row.state)) else row }
        }
    }

    private fun buildCandidates() {
        val existingArns = reactiveClient.getContexts().toSet()
        val pattern = Regex("""arn:aws:eks:([^:]+):\d+:cluster/(.+)""")
        val existingByNameAndRegion: Set<Pair<String, String>> = existingArns.mapNotNull { ctx ->
            pattern.matchEntire(ctx)?.let { it.groupValues[2] to it.groupValues[1] }
        }.toSet()

        val clusters = _scanRows.value.flatMap { row ->
            (row.state as? RegionScanState.Done)?.clusters ?: emptyList()
        }
        _candidates.value = clusters.map { cluster ->
            val already = existingByNameAndRegion.contains(cluster.name to cluster.region)
            ClusterCandidate(
                cluster = cluster,
                alreadyImported = already,
                selected = !already,
            )
        }.sortedWith(compareBy({ it.cluster.region }, { it.cluster.name }))
    }

    fun toggleSelection(cluster: EksCluster) {
        _candidates.update { list ->
            list.map {
                if (it.cluster == cluster) it.copy(selected = !it.selected) else it
            }
        }
    }

    fun selectAll(value: Boolean) {
        _candidates.update { list -> list.map { it.copy(selected = value) } }
    }

    fun startImport() {
        val profile = _selectedProfile.value ?: return
        val toImport = _candidates.value.filter { it.selected }.map { it.cluster }
        if (toImport.isEmpty()) {
            _errorMessage.value = "Select at least one cluster to import."
            return
        }
        activeJob?.cancel()
        _errorMessage.value = null
        _busy.value = true
        _importRows.value = toImport.map { ImportRow(it, ImportRowState.Pending) }
        _step.value = EksDiscoveryStep.IMPORTING

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val kubeconfigPath = KubeconfigLocator.activePath()
            for (cluster in toImport) {
                updateImportRow(cluster) { ImportRowState.Importing }
                val result = withContext(Dispatchers.IO) {
                    EksClusterDiscoverer.importCluster(profile, cluster.region, cluster.name, kubeconfigPath)
                }
                result.fold(
                    onSuccess = { ctx ->
                        updateImportRow(cluster) { ImportRowState.Done(ctx) }
                    },
                    onFailure = { e ->
                        val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                        updateImportRow(cluster) { ImportRowState.Failed(msg) }
                    },
                )
            }
            _busy.value = false
            _step.value = EksDiscoveryStep.DONE
        }
    }

    private fun updateImportRow(cluster: EksCluster, transform: (ImportRowState) -> ImportRowState) {
        _importRows.update { rows ->
            rows.map { row -> if (row.cluster == cluster) row.copy(state = transform(row.state)) else row }
        }
    }

    fun reset() {
        activeJob?.cancel()
        activeJob = null
        _scanRows.value = emptyList()
        _candidates.value = emptyList()
        _importRows.value = emptyList()
        _errorMessage.value = null
        _busy.value = false
        _step.value = EksDiscoveryStep.PICK_PROFILE
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _busy.value = false
    }

    val anyImportSucceeded: Boolean
        get() = _importRows.value.any { it.state is ImportRowState.Done }

    override fun onCleared() {
        activeJob?.cancel()
        super.onCleared()
    }
}
