package com.kubekubedashdash.ui.modals.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.util.EksClusterDiscoverer
import com.kubekubedashdash.util.GcpProject
import com.kubekubedashdash.util.GkeCluster
import com.kubekubedashdash.util.GkeClusterDiscoverer
import com.kubekubedashdash.util.KubeconfigLocator
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

// A real account can have hundreds of GCP projects, and each selected project costs one
// `gcloud` subprocess at Semaphore(6) concurrency.
// This caps bulk selection so `selectAllProjects(true)` cannot trigger a multi-minute,
// hundreds-of-process scan.
private const val MAX_SCAN_PROJECTS = 25

enum class GkeDiscoveryStep { PICK_PROJECTS, SCANNING, PICK_CLUSTERS, IMPORTING, DONE }

sealed class ProjectLoadState {
    object Loading : ProjectLoadState()
    object NotSignedIn : ProjectLoadState()
    data class Failed(val message: String) : ProjectLoadState()
    data class Loaded(val projects: List<GcpProject>) : ProjectLoadState()
}

sealed class ProjectScanState {
    object Pending : ProjectScanState()
    object Scanning : ProjectScanState()
    data class Done(val clusters: List<GkeCluster>) : ProjectScanState()
    data class Failed(val message: String) : ProjectScanState()
}

data class ProjectScanRow(val projectId: String, val state: ProjectScanState)

data class GkeClusterCandidate(
    val cluster: GkeCluster,
    val alreadyImported: Boolean,
    val selected: Boolean,
)

sealed class GkeImportRowState {
    object Pending : GkeImportRowState()
    object Importing : GkeImportRowState()
    data class Done(val contextName: String) : GkeImportRowState()
    data class Failed(val message: String) : GkeImportRowState()
}

data class GkeImportRow(val cluster: GkeCluster, val state: GkeImportRowState)

class GkeDiscoveryViewModel(
    private val reactiveClient: ReactiveKubeClient,
) : ViewModel() {

    private val log = LoggerFactory.getLogger(GkeDiscoveryViewModel::class.java)

    private val _step = MutableStateFlow(GkeDiscoveryStep.PICK_PROJECTS)
    val step: StateFlow<GkeDiscoveryStep> = _step.asStateFlow()

    private val _projectLoadState = MutableStateFlow<ProjectLoadState>(ProjectLoadState.Loading)
    val projectLoadState: StateFlow<ProjectLoadState> = _projectLoadState.asStateFlow()

    private val _activeAccount = MutableStateFlow<String?>(null)
    val activeAccount: StateFlow<String?> = _activeAccount.asStateFlow()

    private val _projectFilter = MutableStateFlow("")
    val projectFilter: StateFlow<String> = _projectFilter.asStateFlow()

    private val _selectedProjects = MutableStateFlow<Set<String>>(emptySet())
    val selectedProjects: StateFlow<Set<String>> = _selectedProjects.asStateFlow()

    // Derived, not the raw cap: MAX_SCAN_PROJECTS stays private to this file (like the
    // per-file GkeBlue declarations), so the modal reacts to this boolean instead of
    // duplicating the magic number.
    val projectSelectionExceedsCap: StateFlow<Boolean> = selectedProjects
        .map { it.size > MAX_SCAN_PROJECTS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _selectionNotice = MutableStateFlow<String?>(null)
    val selectionNotice: StateFlow<String?> = _selectionNotice.asStateFlow()

    private val _scanRows = MutableStateFlow<List<ProjectScanRow>>(emptyList())
    val scanRows: StateFlow<List<ProjectScanRow>> = _scanRows.asStateFlow()

    private val _candidates = MutableStateFlow<List<GkeClusterCandidate>>(emptyList())
    val candidates: StateFlow<List<GkeClusterCandidate>> = _candidates.asStateFlow()

    private val _importRows = MutableStateFlow<List<GkeImportRow>>(emptyList())
    val importRows: StateFlow<List<GkeImportRow>> = _importRows.asStateFlow()

    private val _backupPath = MutableStateFlow<String?>(null)
    val backupPath: StateFlow<String?> = _backupPath.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val gcloudCliAvailable: Boolean = GkeClusterDiscoverer.isGcloudAvailable()

    /** Tracks the scan/import work, so [reset] can cancel a wizard run in flight. */
    private var activeJob: Job? = null

    /**
     * Tracks the account/project load separately from [activeJob]. These MUST stay separate:
     * the modal runs `LaunchedEffect(Unit) { viewModel.reset() }` on open (mirroring the EKS
     * modal), so a load tracked by [activeJob] would be cancelled the moment the modal appeared,
     * leaving [_projectLoadState] stuck on `Loading` forever. The EKS modal gets away with the
     * same reset-on-open because its `loadProfiles()` reads a local ini file on an untracked
     * coroutine; ours is a `gcloud` subprocess and has to survive the reset.
     */
    private var loadJob: Job? = null

    init {
        retryLoad()
    }

    /** Re-runs the account check, and the project list if signed in. Backs D4's "Try again". */
    fun retryLoad() {
        loadJob?.cancel()
        _errorMessage.value = null
        _projectLoadState.value = ProjectLoadState.Loading
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            GkeClusterDiscoverer.activeAccount().fold(
                onSuccess = { account ->
                    _activeAccount.value = account
                    if (account == null) {
                        _projectLoadState.value = ProjectLoadState.NotSignedIn
                        return@launch
                    }
                    loadProjects()
                },
                onFailure = { e ->
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                    log.warn("Failed to resolve active gcloud account: {}", msg)
                    _projectLoadState.value = ProjectLoadState.Failed(msg)
                },
            )
        }
    }

    private suspend fun loadProjects() {
        GkeClusterDiscoverer.listProjects().fold(
            onSuccess = { projects ->
                _projectLoadState.value = ProjectLoadState.Loaded(projects)
                val available = projects.map { it.projectId }.toSet()
                val remembered = PreferenceRepository.lastGcpProjects.value.filter { it in available }
                _selectedProjects.value = remembered.toSet()
            },
            onFailure = { e ->
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                log.warn("Failed to list GCP projects: {}", msg)
                _projectLoadState.value = ProjectLoadState.Failed(msg)
            },
        )
    }

    fun setProjectFilter(text: String) {
        _projectFilter.value = text
    }

    private fun filteredProjects(): List<GcpProject> {
        val loaded = (_projectLoadState.value as? ProjectLoadState.Loaded)?.projects ?: emptyList()
        val filter = _projectFilter.value.trim()
        if (filter.isEmpty()) return loaded
        return loaded.filter {
            it.projectId.contains(filter, ignoreCase = true) || it.displayName.contains(filter, ignoreCase = true)
        }
    }

    fun toggleProject(projectId: String) {
        _selectionNotice.value = null
        _selectedProjects.update { current ->
            if (projectId in current) current - projectId else current + projectId
        }
    }

    /**
     * Selects only the currently filtered projects. If that set exceeds [MAX_SCAN_PROJECTS],
     * selects just the first [MAX_SCAN_PROJECTS] and surfaces a notice explaining why.
     */
    fun selectAllProjects(value: Boolean) {
        if (!value) {
            _selectedProjects.value = emptySet()
            _selectionNotice.value = null
            return
        }
        val filtered = filteredProjects()
        if (filtered.size > MAX_SCAN_PROJECTS) {
            _selectedProjects.value = filtered.take(MAX_SCAN_PROJECTS).map { it.projectId }.toSet()
            _selectionNotice.value =
                "Selected the first $MAX_SCAN_PROJECTS of ${filtered.size} matching projects — narrow the filter to scan others."
        } else {
            _selectedProjects.value = filtered.map { it.projectId }.toSet()
            _selectionNotice.value = null
        }
    }

    fun goToStep(step: GkeDiscoveryStep) {
        _errorMessage.value = null
        _step.value = step
    }

    fun startScan() {
        val projects = _selectedProjects.value
        if (projects.isEmpty() || projects.size > MAX_SCAN_PROJECTS) return
        activeJob?.cancel()
        _errorMessage.value = null
        _busy.value = true
        _step.value = GkeDiscoveryStep.SCANNING
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val ordered = projects.toList().sorted()
            PreferenceRepository.setLastGcpProjects(ordered)
            _scanRows.value = ordered.map { ProjectScanRow(it, ProjectScanState.Pending) }
            scanProjects(ordered)
            buildCandidates()
            _busy.value = false
            _step.value = GkeDiscoveryStep.PICK_CLUSTERS
        }
    }

    private suspend fun scanProjects(projects: List<String>) = coroutineScope {
        val semaphore = Semaphore(permits = 6)
        projects.map { projectId ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    updateScanRow(projectId) { ProjectScanState.Scanning }
                    val result = GkeClusterDiscoverer.listClusters(projectId)
                    result.fold(
                        onSuccess = { clusters ->
                            updateScanRow(projectId) { ProjectScanState.Done(clusters) }
                        },
                        onFailure = { e ->
                            val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                            updateScanRow(projectId) { ProjectScanState.Failed(msg) }
                        },
                    )
                }
            }
        }.awaitAll()
    }

    private fun updateScanRow(projectId: String, transform: (ProjectScanState) -> ProjectScanState) {
        _scanRows.update { rows ->
            rows.map { row -> if (row.projectId == projectId) row.copy(state = transform(row.state)) else row }
        }
    }

    private fun buildCandidates() {
        val existingKeys: Set<Triple<String, String, String>> = reactiveClient.getContexts()
            .mapNotNull { ctx -> GkeClusterDiscoverer.parseGkeContext(ctx) }
            .toSet()

        val clusters = _scanRows.value.flatMap { row ->
            (row.state as? ProjectScanState.Done)?.clusters ?: emptyList()
        }
        _candidates.value = clusters.map { cluster ->
            val key = Triple(cluster.projectId, cluster.location, cluster.name)
            val already = existingKeys.contains(key)
            GkeClusterCandidate(cluster = cluster, alreadyImported = already, selected = !already)
        }.sortedWith(compareBy({ it.cluster.projectId }, { it.cluster.location }, { it.cluster.name }))
    }

    fun toggleSelection(cluster: GkeCluster) {
        _candidates.update { list ->
            list.map { if (it.cluster == cluster) it.copy(selected = !it.selected) else it }
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
        _backupPath.value = null
        _importRows.value = toImport.map { GkeImportRow(it, GkeImportRowState.Pending) }
        _step.value = GkeDiscoveryStep.IMPORTING

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val kubeconfigPath = KubeconfigLocator.activePath()

            // The kubeconfig backup is MANDATORY and BLOCKING here — this differs from the
            // EKS flow. `gcloud container clusters get-credentials` rewrites the entire
            // kubeconfig and silently recreates it from empty if the existing YAML fails
            // gcloud's own validation, so a failed backup must abort the import rather than
            // proceed best-effort.
            val existing = File(kubeconfigPath)
            if (existing.isFile && existing.length() > 0L) {
                val backup = EksClusterDiscoverer.backupKubeconfig(kubeconfigPath)
                if (backup == null) {
                    _errorMessage.value = "Could not back up $kubeconfigPath. " +
                        "gcloud rewrites the whole kubeconfig, so the import was cancelled."
                    _busy.value = false
                    _step.value = GkeDiscoveryStep.PICK_CLUSTERS
                    return@launch
                }
                _backupPath.value = backup.absolutePath
            }

            for (cluster in toImport) {
                updateImportRow(cluster) { GkeImportRowState.Importing }
                val result = withContext(Dispatchers.IO) {
                    GkeClusterDiscoverer.importCluster(cluster.projectId, cluster.location, cluster.name, kubeconfigPath)
                }
                result.fold(
                    onSuccess = { ctx ->
                        updateImportRow(cluster) { GkeImportRowState.Done(ctx) }
                    },
                    onFailure = { e ->
                        val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                        updateImportRow(cluster) { GkeImportRowState.Failed(msg) }
                    },
                )
            }
            _busy.value = false
            _step.value = GkeDiscoveryStep.DONE
        }
    }

    private fun updateImportRow(cluster: GkeCluster, transform: (GkeImportRowState) -> GkeImportRowState) {
        _importRows.update { rows ->
            rows.map { row -> if (row.cluster == cluster) row.copy(state = transform(row.state)) else row }
        }
    }

    /**
     * Clears wizard progress and returns to the first step. Deliberately does NOT cancel
     * [loadJob] — the modal calls this on open, and killing the in-flight sign-in check
     * would strand [_projectLoadState] on `Loading`.
     */
    fun reset() {
        activeJob?.cancel()
        activeJob = null
        _scanRows.value = emptyList()
        _candidates.value = emptyList()
        _importRows.value = emptyList()
        _backupPath.value = null
        _errorMessage.value = null
        _busy.value = false
        _step.value = GkeDiscoveryStep.PICK_PROJECTS
    }

    fun cancel() = reset()

    val anyImportSucceeded: Boolean
        get() = _importRows.value.any { it.state is GkeImportRowState.Done }

    override fun onCleared() {
        activeJob?.cancel()
        loadJob?.cancel()
        super.onCleared()
    }
}
