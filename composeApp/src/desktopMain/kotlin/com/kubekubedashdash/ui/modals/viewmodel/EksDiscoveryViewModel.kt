package com.kubekubedashdash.ui.modals.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.util.AwsProfile
import com.kubekubedashdash.util.AwsProfileReader
import com.kubekubedashdash.util.EksCluster
import com.kubekubedashdash.util.EksClusterDiscoverer
import com.kubekubedashdash.util.KubeconfigLocator
import com.kubekubedashdash.util.ReactiveKubeClient
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

data class RegionScanRow(val profile: String, val region: String, val state: RegionScanState)

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

class EksDiscoveryViewModel(
    private val reactiveClient: ReactiveKubeClient,
) : ViewModel() {

    private val log = LoggerFactory.getLogger(EksDiscoveryViewModel::class.java)

    private val _step = MutableStateFlow(EksDiscoveryStep.PICK_PROFILE)
    val step: StateFlow<EksDiscoveryStep> = _step.asStateFlow()

    private val _profiles = MutableStateFlow<List<AwsProfile>>(emptyList())
    val profiles: StateFlow<List<AwsProfile>> = _profiles.asStateFlow()

    private val _selectedProfiles = MutableStateFlow<Set<String>>(emptySet())
    val selectedProfiles: StateFlow<Set<String>> = _selectedProfiles.asStateFlow()

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
            val available = list.map { it.name }.toSet()
            val remembered = PreferenceRepository.lastAwsProfiles.value.filter { it in available }
            _selectedProfiles.value = when {
                remembered.isNotEmpty() -> remembered.toSet()
                list.isNotEmpty() -> setOf(list.first().name)
                else -> emptySet()
            }
        }
    }

    fun toggleProfile(name: String) {
        _selectedProfiles.update { current ->
            if (name in current) current - name else current + name
        }
    }

    fun selectAllProfiles(value: Boolean) {
        _selectedProfiles.value = if (value) _profiles.value.map { it.name }.toSet() else emptySet()
    }

    fun setRegionScope(scope: RegionScope) {
        _regionScope.value = scope
    }

    fun goToStep(step: EksDiscoveryStep) {
        _errorMessage.value = null
        _step.value = step
    }

    fun proceedFromProfile() {
        val profiles = _selectedProfiles.value
        if (profiles.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            PreferenceRepository.setLastAwsProfiles(profiles.toList().sorted())
        }
        _step.value = EksDiscoveryStep.PICK_REGIONS
    }

    fun startDiscovery() {
        val profiles = _selectedProfiles.value
        if (profiles.isEmpty()) return
        activeJob?.cancel()
        _errorMessage.value = null
        _busy.value = true
        _step.value = EksDiscoveryStep.SCANNING
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val targets = resolveProfileRegions(profiles, _regionScope.value)
            if (targets.isEmpty()) {
                _errorMessage.value = "No regions to scan."
                _busy.value = false
                _step.value = EksDiscoveryStep.PICK_REGIONS
                return@launch
            }
            _scanRows.value = targets.map { (p, r) -> RegionScanRow(p, r, RegionScanState.Pending) }
            scanRegions(targets)
            buildCandidates()
            _busy.value = false
            _step.value = EksDiscoveryStep.PICK_CLUSTERS
        }
    }

    private suspend fun resolveProfileRegions(
        profiles: Set<String>,
        scope: RegionScope,
    ): List<Pair<String, String>> {
        val orderedProfiles = profiles.toList().sorted()
        return orderedProfiles.flatMap { profile ->
            val profileDefault = _profiles.value.firstOrNull { it.name == profile }?.defaultRegion
            val regions: List<String> = when (scope) {
                RegionScope.DEFAULT_ONLY -> listOfNotNull(profileDefault ?: "us-east-1")

                RegionScope.COMMON -> (listOfNotNull(profileDefault) + EksClusterDiscoverer.COMMON_REGIONS).distinct()

                RegionScope.ALL_ENABLED -> EksClusterDiscoverer.listEnabledRegions(profile).getOrElse {
                    log.warn("Falling back to common regions for {}: {}", profile, it.message)
                    EksClusterDiscoverer.COMMON_REGIONS
                }
            }
            regions.map { profile to it }
        }
    }

    private suspend fun scanRegions(targets: List<Pair<String, String>>) = coroutineScope {
        val semaphore = Semaphore(permits = 6)
        targets.map { (profile, region) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    updateScanRow(profile, region) { RegionScanState.Scanning }
                    val result = EksClusterDiscoverer.listClusters(profile, region)
                    result.fold(
                        onSuccess = { clusters ->
                            updateScanRow(profile, region) { RegionScanState.Done(clusters) }
                        },
                        onFailure = { e ->
                            val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                            updateScanRow(profile, region) { RegionScanState.Failed(msg) }
                        },
                    )
                }
            }
        }.awaitAll()
    }

    private fun updateScanRow(profile: String, region: String, transform: (RegionScanState) -> RegionScanState) {
        _scanRows.update { rows ->
            rows.map { row ->
                if (row.profile == profile && row.region == region) row.copy(state = transform(row.state)) else row
            }
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
        }.sortedWith(compareBy({ it.cluster.profile }, { it.cluster.region }, { it.cluster.name }))
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
                    EksClusterDiscoverer.importCluster(cluster.profile, cluster.region, cluster.name, kubeconfigPath)
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

    fun cancel() = reset()

    val anyImportSucceeded: Boolean
        get() = _importRows.value.any { it.state is ImportRowState.Done }

    override fun onCleared() {
        activeJob?.cancel()
        super.onCleared()
    }
}
