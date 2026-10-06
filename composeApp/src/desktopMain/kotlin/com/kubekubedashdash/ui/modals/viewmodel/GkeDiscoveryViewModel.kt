package com.kubekubedashdash.ui.modals.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.util.ClusterReferenceParser
import com.kubekubedashdash.util.GcpProject
import com.kubekubedashdash.util.GkeCluster
import com.kubekubedashdash.util.GkeClusterDiscoverer
import com.kubekubedashdash.util.displayPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// A real account can have hundreds of GCP projects, and each selected project costs one
// `gcloud` subprocess at Semaphore(6) concurrency.
// This caps bulk selection so `selectAllProjects(true)` cannot trigger a multi-minute,
// hundreds-of-process scan.
private const val MAX_SCAN_PROJECTS = 25

private const val MAX_SUGGESTIONS = 8

enum class GkeDiscoveryStep { PICK_PROJECTS, SCANNING, PICK_CLUSTERS, IMPORTING, DONE }

sealed class ProjectLoadState {
    object Loading : ProjectLoadState()

    /** Signed in, but the project list is not loaded because the by-name tab is shown. */
    object NotRequested : ProjectLoadState()

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
    object Cancelled : GkeImportRowState()
    data class Done(val contextName: String) : GkeImportRowState()
    data class Failed(val message: String) : GkeImportRowState()
}

data class GkeImportRow(val cluster: GkeCluster, val state: GkeImportRowState)

internal const val GKE_PROJECT_ERROR =
    "A project ID has 6 to 30 lowercase letters, digits or hyphens and starts with a letter."
internal const val GKE_LOCATION_ERROR = "Use a region such as europe-west1 or a zone such as europe-west1-b."
internal const val GKE_CLUSTER_ERROR =
    "A cluster name has up to 40 lowercase letters, digits or hyphens and starts with a letter."
internal const val GKE_PASTE_NOT_RECOGNIZED =
    "Not recognized. Paste a gcloud … get-credentials command, a gke_… context name " +
        "or a projects/…/locations/…/clusters/… path."

data class GkeByNameSuggestions(val projects: List<String>, val locations: List<String>)

data class GkeByNameState(
    val projectError: String?,
    val locationError: String?,
    val clusterError: String?,
    val alreadyImported: Boolean,
    val canImport: Boolean,
) {
    companion object {
        val EMPTY = GkeByNameState(null, null, null, alreadyImported = false, canImport = false)
    }
}

/** Field checks for the by-name tab. Inputs are already trimmed. A blank field has no error. */
internal fun gkeByNameState(
    project: String,
    location: String,
    cluster: String,
    existing: Set<Triple<String, String, String>>,
): GkeByNameState {
    val projectOk = GkeClusterDiscoverer.isValidProjectId(project)
    val locationOk = GkeClusterDiscoverer.isValidLocation(location)
    val clusterOk = GkeClusterDiscoverer.isValidClusterName(cluster)
    return GkeByNameState(
        projectError = GKE_PROJECT_ERROR.takeIf { project.isNotEmpty() && !projectOk },
        locationError = GKE_LOCATION_ERROR.takeIf { location.isNotEmpty() && !locationOk },
        clusterError = GKE_CLUSTER_ERROR.takeIf { cluster.isNotEmpty() && !clusterOk },
        alreadyImported = Triple(project, location, cluster) in existing,
        canImport = projectOk && locationOk && clusterOk,
    )
}

class GkeDiscoveryViewModel(
    private val gateway: GkeDiscoveryGateway = DefaultGkeDiscoveryGateway(),
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

    val gcloudCliAvailable: Boolean = gateway.gcloudAvailable

    /** Whether `gke-gcloud-auth-plugin` resolves on PATH — drives the modal's advisory banner. */
    val authPluginAvailable: Boolean get() = gateway.authPluginAvailable

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

    /**
     * Monotonic run token. Bumped by [reset], [startScan] and [startImport]; every
     * asynchronous state write is tagged with the generation captured when its run
     * started and is dropped when a newer run (or a reset) has superseded it. Closes
     * two races: a cancelled scan's completion callback — already past its last
     * cancellation check on a blocked IO thread — landing in a re-run's rows with the
     * same project id, and an abandoned import job's finally-block yanking a reopened
     * wizard to DONE.
     */
    private val runGeneration = AtomicInteger(0)

    private val _cancelRequested = MutableStateFlow(false)
    val cancelRequested: StateFlow<Boolean> = _cancelRequested.asStateFlow()

    private val _mode = MutableStateFlow(gateway.recallDiscoveryMode())
    val mode: StateFlow<DiscoveryMode> = _mode.asStateFlow()

    private val _byNameProject = MutableStateFlow("")
    val byNameProject: StateFlow<String> = _byNameProject.asStateFlow()

    private val _byNameLocation = MutableStateFlow("")
    val byNameLocation: StateFlow<String> = _byNameLocation.asStateFlow()

    private val _byNameCluster = MutableStateFlow("")
    val byNameCluster: StateFlow<String> = _byNameCluster.asStateFlow()

    private val _pasteText = MutableStateFlow("")
    val pasteText: StateFlow<String> = _pasteText.asStateFlow()

    private val _pasteNotice = MutableStateFlow<PasteNotice?>(null)
    val pasteNotice: StateFlow<PasteNotice?> = _pasteNotice.asStateFlow()

    /** (project, location, cluster) of every GKE context already in the kubeconfig. */
    private val existingKeys = MutableStateFlow<Set<Triple<String, String, String>>>(emptySet())

    private val _byNameSuggestions = MutableStateFlow(GkeByNameSuggestions(emptyList(), emptyList()))
    val byNameSuggestions: StateFlow<GkeByNameSuggestions> = _byNameSuggestions.asStateFlow()

    val byNameState: StateFlow<GkeByNameState> =
        combine(_byNameProject, _byNameLocation, _byNameCluster, existingKeys) { project, location, cluster, keys ->
            gkeByNameState(project.trim(), location.trim(), cluster.trim(), keys)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GkeByNameState.EMPTY)

    /**
     * Sticky "something reached the kubeconfig since the modal opened". [addAnotherByName] and
     * [cancel] keep it, so closing the modal after several by-name runs still refreshes the
     * parent's cluster list; only [reset] (modal open) clears it. Written on IO, read on Main.
     */
    @Volatile
    private var importedSinceOpen = false

    init {
        refreshExistingContexts()
        retryLoad()
    }

    /**
     * Re-runs the account check, and the project list if signed in and the Browse tab is shown.
     * Backs D4's "Try again".
     */
    fun retryLoad() {
        loadJob?.cancel()
        _errorMessage.value = null
        _projectLoadState.value = ProjectLoadState.Loading
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            gateway.activeAccount().fold(
                onSuccess = { account ->
                    _activeAccount.value = account
                    if (account == null) {
                        _projectLoadState.value = ProjectLoadState.NotSignedIn
                        return@launch
                    }
                    _projectLoadState.value = ProjectLoadState.NotRequested
                    // Publish, then check: setMode publishes _mode and then tries the same
                    // compare-and-set, so whichever side runs second sees the other's write and
                    // exactly one of them wins the load.
                    if (_mode.value == DiscoveryMode.BROWSE &&
                        _projectLoadState.compareAndSet(ProjectLoadState.NotRequested, ProjectLoadState.Loading)
                    ) {
                        loadProjects()
                    }
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
        gateway.listProjects().fold(
            onSuccess = { projects ->
                _projectLoadState.value = ProjectLoadState.Loaded(projects)
                val available = projects.map { it.projectId }.toSet()
                val remembered = gateway.recallProjectSelection().filter { it in available }
                _selectedProjects.value = remembered.toSet()
            },
            onFailure = { e ->
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                log.warn("Failed to list GCP projects: {}", msg)
                _projectLoadState.value = ProjectLoadState.Failed(msg)
            },
        )
    }

    /** Called from the tab row. Persists the tab; the Browse tab loads projects on first show. */
    fun setMode(mode: DiscoveryMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        gateway.rememberDiscoveryMode(mode)
        _errorMessage.value = null
        // Pairs with the compare-and-set in retryLoad: exactly one side starts the load.
        if (mode == DiscoveryMode.BROWSE &&
            _projectLoadState.compareAndSet(ProjectLoadState.NotRequested, ProjectLoadState.Loading)
        ) {
            loadJob = viewModelScope.launch(Dispatchers.IO) { loadProjects() }
        }
    }

    /** From a dead end in the browse flow (no clusters found, failed scans). */
    fun switchToByName() {
        cancel()
        setMode(DiscoveryMode.BY_NAME)
    }

    fun setByNameProject(value: String) {
        _byNameProject.value = value
    }

    fun setByNameLocation(value: String) {
        _byNameLocation.value = value
    }

    fun setByNameCluster(value: String) {
        _byNameCluster.value = value
    }

    /** Live: every change is parsed; recognized values overwrite the fields. Never executed. */
    fun onPasteTextChange(text: String) {
        _pasteText.value = text
        if (text.isBlank()) {
            _pasteNotice.value = null
            return
        }
        val ref = ClusterReferenceParser.parseGke(text)
        if (ref == null) {
            _pasteNotice.value = PasteNotice(GKE_PASTE_NOT_RECOGNIZED, recognized = false)
            return
        }
        ref.projectId?.let { _byNameProject.value = it }
        ref.location?.let { _byNameLocation.value = it }
        ref.clusterName?.let { _byNameCluster.value = it }
        val filled = listOfNotNull(
            "project".takeIf { ref.projectId != null },
            "location".takeIf { ref.location != null },
            "cluster".takeIf { ref.clusterName != null },
        )
        val missing = listOf("project", "location", "cluster") - filled.toSet()
        _pasteNotice.value = PasteNotice(filledNotice(filled, missing), recognized = true)
    }

    fun startByNameImport() {
        if (_busy.value) return
        val project = _byNameProject.value.trim()
        val location = _byNameLocation.value.trim()
        val name = _byNameCluster.value.trim()
        // Computed from the fields, not read from byNameState: that flow updates one dispatch later.
        val state = gkeByNameState(project, location, name, existingKeys.value)
        if (!state.canImport) return
        val cluster = GkeCluster(name = name, location = location, projectId = project, status = null)
        _candidates.value = listOf(GkeClusterCandidate(cluster = cluster, alreadyImported = state.alreadyImported, selected = true))
        launchImport(returnStep = GkeDiscoveryStep.PICK_PROJECTS)
    }

    /** DONE of a by-name run → back to the form, keeping project and location. */
    fun addAnotherByName() {
        if (_step.value != GkeDiscoveryStep.DONE) return
        resetRun()
        _byNameCluster.value = ""
        _pasteText.value = ""
        _pasteNotice.value = null
        refreshExistingContexts()
    }

    private fun refreshExistingContexts() {
        viewModelScope.launch(Dispatchers.IO) {
            val keys = runCatching { gateway.existingContexts() }.getOrElse { emptyList() }
                .mapNotNull { gateway.parseContext(it) }
                .toSet()
            existingKeys.value = keys
            _byNameSuggestions.value = GkeByNameSuggestions(
                projects = keys.map { it.first }.distinct().sorted().take(MAX_SUGGESTIONS),
                locations = keys.map { it.second }.distinct().sorted().take(MAX_SUGGESTIONS),
            )
        }
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
        // Bump BEFORE cancelling (same rule as reset()): the dying job's
        // finally runs on an IO thread concurrently with this one, and in a
        // cancel-then-bump window its generation check still passes, letting its
        // DONE/busy=false writes land on top of the run we are starting here.
        val gen = runGeneration.incrementAndGet()
        activeJob?.cancel()
        _errorMessage.value = null
        _busy.value = true
        _step.value = GkeDiscoveryStep.SCANNING
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val ordered = projects.toList().sorted()
            gateway.rememberProjectSelection(ordered)
            _scanRows.value = ordered.map { ProjectScanRow(it, ProjectScanState.Pending) }
            scanProjects(gen, ordered)
            if (gen == runGeneration.get()) {
                buildCandidates()
                _busy.value = false
                _step.value = GkeDiscoveryStep.PICK_CLUSTERS
            }
        }
    }

    private suspend fun scanProjects(generation: Int, projects: List<String>) = coroutineScope {
        val semaphore = Semaphore(permits = 6)
        projects.map { projectId ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    updateScanRow(generation, projectId) { ProjectScanState.Scanning }
                    val result = gateway.listClusters(projectId)
                    result.fold(
                        onSuccess = { clusters ->
                            updateScanRow(generation, projectId) { ProjectScanState.Done(clusters) }
                        },
                        onFailure = { e ->
                            val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                            updateScanRow(generation, projectId) { ProjectScanState.Failed(msg) }
                        },
                    )
                }
            }
        }.awaitAll()
    }

    private fun updateScanRow(generation: Int, projectId: String, transform: (ProjectScanState) -> ProjectScanState) {
        _scanRows.update { rows ->
            if (generation != runGeneration.get()) return@update rows
            rows.map { row -> if (row.projectId == projectId) row.copy(state = transform(row.state)) else row }
        }
    }

    private fun buildCandidates() {
        val existingKeys: Set<Triple<String, String, String>> = gateway.existingContexts()
            .mapNotNull { ctx -> gateway.parseContext(ctx) }
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

    fun startImport() = launchImport(returnStep = GkeDiscoveryStep.PICK_CLUSTERS)

    private fun launchImport(returnStep: GkeDiscoveryStep) {
        val toImport = _candidates.value.filter { it.selected }.map { it.cluster }
        if (toImport.isEmpty()) {
            _errorMessage.value = "Select at least one cluster to import."
            return
        }
        // Bump BEFORE cancelling — see the note in startScan.
        val gen = runGeneration.incrementAndGet()
        activeJob?.cancel()
        _errorMessage.value = null
        _busy.value = true
        _cancelRequested.value = false
        _backupPath.value = null
        _importRows.value = toImport.map { GkeImportRow(it, GkeImportRowState.Pending) }
        _step.value = GkeDiscoveryStep.IMPORTING

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val kubeconfigPath = gateway.kubeconfigPath()

            // The kubeconfig backup is MANDATORY and BLOCKING here — this differs from the
            // EKS flow. `gcloud container clusters get-credentials` rewrites the entire
            // kubeconfig and silently recreates it from empty if the existing YAML fails
            // gcloud's own validation, so a failed backup must abort the import rather than
            // proceed best-effort.
            val existing = File(kubeconfigPath)
            if (existing.isFile && existing.length() > 0L) {
                val backup = gateway.backupKubeconfig(kubeconfigPath)
                if (backup == null) {
                    // Guarded: this abort writes state from a launched job, so a run
                    // abandoned by X-close + reopen must not yank the reopened wizard's
                    // step and error state. The return stays outside the guard — an
                    // abandoned run must still stop.
                    if (gen == runGeneration.get()) {
                        _errorMessage.value = "Could not back up ${displayPath(kubeconfigPath)}. " +
                            "gcloud rewrites the whole kubeconfig, so the import was cancelled."
                        _busy.value = false
                        _step.value = returnStep
                    }
                    return@launch
                }
                _backupPath.value = displayPath(backup.absolutePath)
            }

            try {
                for (cluster in toImport) {
                    // Cancellation (requestCancelImport / reset) takes effect HERE,
                    // between clusters — never mid-import.
                    ensureActive()
                    updateImportRow(gen, cluster) { GkeImportRowState.Importing }
                    // NonCancellable: once get-credentials is running it must finish.
                    // gcloud saves the kubeconfig by truncate-and-rewrite, so killing
                    // the child mid-write can corrupt the file.
                    val result = withContext(NonCancellable) {
                        gateway.importCluster(cluster.projectId, cluster.location, cluster.name, kubeconfigPath)
                    }
                    result.fold(
                        onSuccess = { ctx ->
                            importedSinceOpen = true
                            updateImportRow(gen, cluster) { GkeImportRowState.Done(ctx) }
                        },
                        onFailure = { e ->
                            val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                            updateImportRow(gen, cluster) { GkeImportRowState.Failed(GkeClusterDiscoverer.describeImportFailure(msg)) }
                        },
                    )
                }
            } finally {
                // Runs on normal completion AND cancellation. On cancellation, rows not
                // yet imported become Cancelled and the wizard still lands on DONE, so
                // the user sees exactly which clusters reached the kubeconfig. The
                // generation guard keeps an abandoned job (modal closed and reopened,
                // reset() ran) from touching the new run's state.
                if (gen == runGeneration.get()) {
                    _importRows.update { rows ->
                        rows.map { row ->
                            when (row.state) {
                                GkeImportRowState.Pending, GkeImportRowState.Importing ->
                                    row.copy(state = GkeImportRowState.Cancelled)

                                else -> row
                            }
                        }
                    }
                    _busy.value = false
                    _cancelRequested.value = false
                    _step.value = GkeDiscoveryStep.DONE
                }
            }
        }
    }

    /**
     * Requests a graceful stop of the import loop. The in-flight `get-credentials`
     * child is deliberately NOT killed — gcloud rewrites the kubeconfig in place
     * (truncate + dump, not atomic), so a kill mid-write can corrupt it. The loop
     * stops before the next cluster and lands on DONE, where the user sees exactly
     * which clusters were imported before the stop.
     */
    fun requestCancelImport() {
        if (_step.value != GkeDiscoveryStep.IMPORTING) return
        _cancelRequested.value = true
        activeJob?.cancel()
    }

    private fun updateImportRow(generation: Int, cluster: GkeCluster, transform: (GkeImportRowState) -> GkeImportRowState) {
        _importRows.update { rows ->
            if (generation != runGeneration.get()) return@update rows
            rows.map { row -> if (row.cluster == cluster) row.copy(state = transform(row.state)) else row }
        }
    }

    /** Clears wizard progress and returns to the first step; the by-name fields and [importedSinceOpen] stay. */
    private fun resetRun() {
        // Bump BEFORE cancelling: any in-flight callback that re-checks the
        // generation is then already stale.
        runGeneration.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
        _scanRows.value = emptyList()
        _candidates.value = emptyList()
        _importRows.value = emptyList()
        _backupPath.value = null
        _errorMessage.value = null
        _busy.value = false
        _cancelRequested.value = false
        _step.value = GkeDiscoveryStep.PICK_PROJECTS
    }

    /**
     * Modal open: clears wizard progress, returns to the first step, forgets earlier imports and
     * the by-name fields. Deliberately does NOT cancel [loadJob] — the modal calls this on open,
     * and killing the in-flight sign-in check would strand [_projectLoadState] on `Loading`.
     * Nor does it re-run [retryLoad]: the view model outlives the modal (it is scoped to the
     * window), so the account check runs once per window; "Try again" re-runs it. Do not add a
     * retryLoad() call here.
     */
    fun reset() {
        resetRun()
        importedSinceOpen = false
        _byNameProject.value = ""
        _byNameLocation.value = ""
        _byNameCluster.value = ""
        _pasteText.value = ""
        _pasteNotice.value = null
        refreshExistingContexts()
    }

    /** Stops the current run and returns to the first step; keeps [importedSinceOpen] and the fields. */
    fun cancel() = resetRun()

    val anyImportSucceeded: Boolean
        get() = importedSinceOpen || _importRows.value.any { it.state is GkeImportRowState.Done }

    override fun onCleared() {
        activeJob?.cancel()
        loadJob?.cancel()
        super.onCleared()
    }
}
