package com.kubekubedashdash.ui.modals.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.util.AwsProfile
import com.kubekubedashdash.util.ClusterReferenceParser
import com.kubekubedashdash.util.EksCluster
import com.kubekubedashdash.util.EksClusterDiscoverer
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

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
    object Cancelled : ImportRowState()
    data class Done(val contextName: String) : ImportRowState()
    data class Failed(val message: String) : ImportRowState()
}

data class ImportRow(val cluster: EksCluster, val state: ImportRowState)

private const val MAX_SUGGESTIONS = 8

internal const val EKS_PROFILE_ERROR = "This profile isn't in your AWS config."
internal const val EKS_REGION_ERROR = "Use an AWS region such as us-east-1 or us-gov-west-1."
internal const val EKS_CLUSTER_ERROR =
    "A cluster name has up to 100 letters, digits, hyphens or underscores and starts with a letter or digit."
internal const val EKS_PASTE_NOT_RECOGNIZED = "Not recognized. Paste an aws eks update-kubeconfig command or a cluster ARN."

data class EksByNameState(
    val profileError: String?,
    val regionError: String?,
    val clusterError: String?,
    val alreadyImported: Boolean,
    val canImport: Boolean,
) {
    companion object {
        val EMPTY = EksByNameState(null, null, null, alreadyImported = false, canImport = false)
    }
}

/** Inputs already trimmed. [existing] holds (cluster name, region) of EKS contexts. */
internal fun eksByNameState(
    profile: String,
    region: String,
    cluster: String,
    existing: Set<Pair<String, String>>,
    profileNames: Set<String>,
): EksByNameState {
    val profileOk = profile in profileNames
    val regionOk = EksClusterDiscoverer.isValidRegion(region)
    val clusterOk = EksClusterDiscoverer.isValidClusterName(cluster)
    return EksByNameState(
        profileError = EKS_PROFILE_ERROR.takeIf { profile.isNotEmpty() && !profileOk },
        regionError = EKS_REGION_ERROR.takeIf { region.isNotEmpty() && !regionOk },
        clusterError = EKS_CLUSTER_ERROR.takeIf { cluster.isNotEmpty() && !clusterOk },
        alreadyImported = (cluster to region) in existing,
        canImport = profileOk && regionOk && clusterOk,
    )
}

class EksDiscoveryViewModel(
    private val gateway: EksDiscoveryGateway = DefaultEksDiscoveryGateway(),
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

    val awsCliAvailable: Boolean = gateway.awsCliAvailable

    private var activeJob: Job? = null

    /**
     * Monotonic run token. Bumped by [reset], [startDiscovery] and [startImport]; every
     * asynchronous state write is tagged with the generation captured when its run
     * started and is dropped when a newer run (or a reset) has superseded it. Closes
     * two races: a cancelled scan's completion callback — already past its last
     * cancellation check on a blocked IO thread — landing in a re-run's rows with the
     * same profile/region, and an abandoned import job's finally-block yanking a
     * reopened wizard to DONE.
     */
    private val runGeneration = AtomicInteger(0)

    private val _cancelRequested = MutableStateFlow(false)
    val cancelRequested: StateFlow<Boolean> = _cancelRequested.asStateFlow()

    private val _mode = MutableStateFlow(gateway.recallDiscoveryMode())
    val mode: StateFlow<DiscoveryMode> = _mode.asStateFlow()

    private val _byNameProfile = MutableStateFlow("")
    val byNameProfile: StateFlow<String> = _byNameProfile.asStateFlow()

    private val _byNameRegion = MutableStateFlow("")
    val byNameRegion: StateFlow<String> = _byNameRegion.asStateFlow()

    private val _byNameCluster = MutableStateFlow("")
    val byNameCluster: StateFlow<String> = _byNameCluster.asStateFlow()

    private val _pasteText = MutableStateFlow("")
    val pasteText: StateFlow<String> = _pasteText.asStateFlow()

    private val _pasteNotice = MutableStateFlow<PasteNotice?>(null)
    val pasteNotice: StateFlow<PasteNotice?> = _pasteNotice.asStateFlow()

    /** (cluster name, region) of every EKS context already in the kubeconfig. */
    private val existingKeys = MutableStateFlow<Set<Pair<String, String>>>(emptySet())

    private val contextRegions = MutableStateFlow<List<String>>(emptyList())

    /** Selected profile's default region first, then regions of EKS contexts, then the common list. */
    val byNameRegionSuggestions: StateFlow<List<String>> =
        combine(_byNameProfile, _profiles, contextRegions) { profile, profiles, regions ->
            (listOfNotNull(profiles.firstOrNull { it.name == profile }?.defaultRegion) + regions + gateway.commonRegions)
                .distinct()
                .take(MAX_SUGGESTIONS)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, gateway.commonRegions.take(MAX_SUGGESTIONS))

    val byNameState: StateFlow<EksByNameState> =
        combine(_byNameProfile, _byNameRegion, _byNameCluster, existingKeys, _profiles) { profile, region, cluster, keys, profiles ->
            eksByNameState(profile.trim(), region.trim(), cluster.trim(), keys, profiles.map { it.name }.toSet())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, EksByNameState.EMPTY)

    /**
     * Sticky "something reached the kubeconfig since the modal opened". [addAnotherByName] and
     * [cancel] keep it, so closing the modal after several by-name runs still refreshes the
     * parent's cluster list; only [reset] (modal open) clears it. Written on IO, read on Main.
     */
    @Volatile
    private var importedSinceOpen = false

    init {
        loadProfiles()
        refreshExistingContexts()
    }

    fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = gateway.listProfiles()
            _profiles.value = list
            val available = list.map { it.name }.toSet()
            val remembered = gateway.recallProfileSelection().filter { it in available }
            _selectedProfiles.value = when {
                remembered.isNotEmpty() -> remembered.toSet()
                list.isNotEmpty() -> setOf(list.first().name)
                else -> emptySet()
            }
            if (list.none { it.name == _byNameProfile.value }) {
                _byNameProfile.value = remembered.firstOrNull() ?: list.firstOrNull()?.name ?: ""
            }
        }
    }

    /** Called from the tab row. Persists the tab; the browse tab runs no command until "Next". */
    fun setMode(mode: DiscoveryMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        gateway.rememberDiscoveryMode(mode)
        _errorMessage.value = null
    }

    /** From a dead end in the browse flow (no clusters found, failed scans). */
    fun switchToByName() {
        cancel()
        setMode(DiscoveryMode.BY_NAME)
    }

    fun setByNameProfile(value: String) {
        _byNameProfile.value = value
    }

    fun setByNameRegion(value: String) {
        _byNameRegion.value = value
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
        val ref = ClusterReferenceParser.parseEks(text)
        if (ref == null) {
            _pasteNotice.value = PasteNotice(EKS_PASTE_NOT_RECOGNIZED, recognized = false)
            return
        }
        val filled = mutableListOf<String>()
        var profileNote = ""
        ref.profile?.let { pasted ->
            if (_profiles.value.any { it.name == pasted }) {
                _byNameProfile.value = pasted
                filled += "profile"
            } else {
                profileNote = " The profile “$pasted” isn't in your AWS config, so it was not selected."
            }
        }
        ref.region?.let {
            _byNameRegion.value = it
            filled += "region"
        }
        ref.clusterName?.let {
            _byNameCluster.value = it
            filled += "cluster"
        }
        val missing = listOfNotNull("region".takeIf { ref.region == null }, "cluster".takeIf { ref.clusterName == null })
        _pasteNotice.value = PasteNotice(filledNotice(filled, missing) + profileNote, recognized = true)
    }

    fun startByNameImport() {
        if (_busy.value) return
        val profile = _byNameProfile.value.trim()
        val region = _byNameRegion.value.trim()
        val name = _byNameCluster.value.trim()
        val state = eksByNameState(profile, region, name, existingKeys.value, _profiles.value.map { it.name }.toSet())
        if (!state.canImport) return
        val cluster = EksCluster(name = name, region = region, profile = profile)
        _candidates.value = listOf(ClusterCandidate(cluster = cluster, alreadyImported = state.alreadyImported, selected = true))
        startImport()
    }

    /** DONE of a by-name run → back to the form, keeping profile and region. */
    fun addAnotherByName() {
        if (_step.value != EksDiscoveryStep.DONE) return
        resetRun()
        _byNameCluster.value = ""
        _pasteText.value = ""
        _pasteNotice.value = null
        refreshExistingContexts()
    }

    private fun refreshExistingContexts() {
        viewModelScope.launch(Dispatchers.IO) {
            val refs = runCatching { gateway.existingContexts() }.getOrElse { emptyList() }
                .mapNotNull { gateway.parseContext(it) }
            existingKeys.value = refs.map { it.clusterName to it.region }.toSet()
            contextRegions.value = refs.map { it.region }.distinct().sorted()
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
            gateway.rememberProfileSelection(profiles.toList().sorted())
        }
        _step.value = EksDiscoveryStep.PICK_REGIONS
    }

    fun startDiscovery() {
        val profiles = _selectedProfiles.value
        if (profiles.isEmpty()) return
        // Bump BEFORE cancelling (same rule as reset()): the dying job's
        // finally runs on an IO thread concurrently with this one, and in a
        // cancel-then-bump window its generation check still passes, letting its
        // DONE/busy=false writes land on top of the run we are starting here.
        val gen = runGeneration.incrementAndGet()
        activeJob?.cancel()
        _errorMessage.value = null
        _busy.value = true
        _step.value = EksDiscoveryStep.SCANNING
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val targets = resolveProfileRegions(profiles, _regionScope.value)
            if (targets.isEmpty()) {
                // Guarded: this abort writes state from a launched job, so a run
                // abandoned by X-close + reopen must not yank the reopened wizard's
                // step and error state. The return stays outside the guard — an
                // abandoned run must still stop.
                if (gen == runGeneration.get()) {
                    _errorMessage.value = "No regions to scan."
                    _busy.value = false
                    _step.value = EksDiscoveryStep.PICK_REGIONS
                }
                return@launch
            }
            _scanRows.value = targets.map { (p, r) -> RegionScanRow(p, r, RegionScanState.Pending) }
            scanRegions(gen, targets)
            if (gen == runGeneration.get()) {
                buildCandidates()
                _busy.value = false
                _step.value = EksDiscoveryStep.PICK_CLUSTERS
            }
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

                RegionScope.COMMON -> (listOfNotNull(profileDefault) + gateway.commonRegions).distinct()

                RegionScope.ALL_ENABLED -> gateway.listEnabledRegions(profile).getOrElse {
                    log.warn("Falling back to common regions for {}: {}", profile, it.message)
                    gateway.commonRegions
                }
            }
            regions.map { profile to it }
        }
    }

    private suspend fun scanRegions(generation: Int, targets: List<Pair<String, String>>) = coroutineScope {
        val semaphore = Semaphore(permits = 6)
        targets.map { (profile, region) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    updateScanRow(generation, profile, region) { RegionScanState.Scanning }
                    val result = gateway.listClusters(profile, region)
                    result.fold(
                        onSuccess = { clusters ->
                            updateScanRow(generation, profile, region) { RegionScanState.Done(clusters) }
                        },
                        onFailure = { e ->
                            val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                            updateScanRow(generation, profile, region) { RegionScanState.Failed(msg) }
                        },
                    )
                }
            }
        }.awaitAll()
    }

    private fun updateScanRow(generation: Int, profile: String, region: String, transform: (RegionScanState) -> RegionScanState) {
        _scanRows.update { rows ->
            if (generation != runGeneration.get()) return@update rows
            rows.map { row ->
                if (row.profile == profile && row.region == region) row.copy(state = transform(row.state)) else row
            }
        }
    }

    private fun buildCandidates() {
        val existingByNameAndRegion: Set<Pair<String, String>> = gateway.existingContexts()
            .mapNotNull { gateway.parseContext(it) }
            .map { it.clusterName to it.region }
            .toSet()

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
        // Bump BEFORE cancelling — see the note in startDiscovery.
        val gen = runGeneration.incrementAndGet()
        activeJob?.cancel()
        _errorMessage.value = null
        _busy.value = true
        _cancelRequested.value = false
        _importRows.value = toImport.map { ImportRow(it, ImportRowState.Pending) }
        _step.value = EksDiscoveryStep.IMPORTING

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val kubeconfigPath = gateway.kubeconfigPath()
            // Snapshot the kubeconfig once before the first update-kubeconfig
            // mutates it, so a bad merge is recoverable.
            gateway.backupKubeconfig(kubeconfigPath)
            try {
                for (cluster in toImport) {
                    // Cancellation (requestCancelImport / reset) takes effect HERE,
                    // between clusters — never mid-import.
                    ensureActive()
                    updateImportRow(gen, cluster) { ImportRowState.Importing }
                    // NonCancellable: once update-kubeconfig is running it must finish.
                    // aws rewrites the kubeconfig in place (truncate + dump, not
                    // atomic), so killing the child mid-write can corrupt the file.
                    val result = withContext(NonCancellable) {
                        gateway.importCluster(cluster.profile, cluster.region, cluster.name, kubeconfigPath)
                    }
                    result.fold(
                        onSuccess = { ctx ->
                            importedSinceOpen = true
                            updateImportRow(gen, cluster) { ImportRowState.Done(ctx) }
                        },
                        onFailure = { e ->
                            val msg = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                            val described = EksClusterDiscoverer.describeImportFailure(msg, cluster.region)
                            updateImportRow(gen, cluster) { ImportRowState.Failed(described) }
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
                                ImportRowState.Pending, ImportRowState.Importing ->
                                    row.copy(state = ImportRowState.Cancelled)

                                else -> row
                            }
                        }
                    }
                    _busy.value = false
                    _cancelRequested.value = false
                    _step.value = EksDiscoveryStep.DONE
                }
            }
        }
    }

    /**
     * Requests a graceful stop of the import loop. The in-flight `update-kubeconfig`
     * child is deliberately NOT killed — `aws eks update-kubeconfig` rewrites the
     * kubeconfig in place (truncate + dump, not atomic), so a kill mid-write can
     * corrupt it. The loop stops before the next cluster and lands on DONE, where
     * the user sees exactly which clusters were imported before the stop.
     */
    fun requestCancelImport() {
        if (_step.value != EksDiscoveryStep.IMPORTING) return
        _cancelRequested.value = true
        activeJob?.cancel()
    }

    private fun updateImportRow(generation: Int, cluster: EksCluster, transform: (ImportRowState) -> ImportRowState) {
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
        _errorMessage.value = null
        _busy.value = false
        _cancelRequested.value = false
        _step.value = EksDiscoveryStep.PICK_PROFILE
    }

    /**
     * Modal open: clears wizard progress, returns to the first step, forgets earlier imports and
     * the by-name region, cluster and paste text. The by-name profile is kept. The view model
     * outlives the modal (it is scoped to the window), so this does not reload the profiles.
     */
    fun reset() {
        resetRun()
        importedSinceOpen = false
        _byNameRegion.value = ""
        _byNameCluster.value = ""
        _pasteText.value = ""
        _pasteNotice.value = null
        refreshExistingContexts()
    }

    /** Stops the current run and returns to the first step; keeps [importedSinceOpen] and the fields. */
    fun cancel() = resetRun()

    val anyImportSucceeded: Boolean
        get() = importedSinceOpen || _importRows.value.any { it.state is ImportRowState.Done }

    override fun onCleared() {
        activeJob?.cancel()
        super.onCleared()
    }
}
