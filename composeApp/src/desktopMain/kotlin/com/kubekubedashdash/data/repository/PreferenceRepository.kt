package com.kubekubedashdash.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.data.datastore.dataStorePreferencesInstance
import com.kubekubedashdash.model.CloseTabFocus
import com.kubekubedashdash.model.TabStripVisibility
import com.kubekubedashdash.ui.screens.allclusters.EventTriagePreset
import com.kubekubedashdash.util.DemoClusterSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PreferenceRepository {
    const val DEFAULT_LOG_DRAWER_HEIGHT_DP = 308
    const val MIN_LOG_DRAWER_HEIGHT_DP = 140
    const val MAX_LOG_DRAWER_HEIGHT_DP = 800

    /** Keys into [statsPanelsExpanded] — one per screen with a collapsible stats panel. */
    const val STATS_PANEL_CLUSTER = "cluster"
    const val STATS_PANEL_PODS = "pods"
    const val STATS_PANEL_NODES = "nodes"
    const val STATS_PANEL_ALL_CLUSTERS = "all_clusters"

    private val dataStore: DataStore<Preferences> by lazy { dataStorePreferencesInstance }
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    // ── Preference keys ───────────────────────────────────────────────────────
    private val THEME_MODE by lazy { stringPreferencesKey("theme_mode") }
    private val MCP_SERVER_ENABLED by lazy { booleanPreferencesKey("mcp_server_enabled") }
    private val MCP_SERVER_PORT by lazy { intPreferencesKey("mcp_server_port") }
    private val MCP_LOCALHOST_ONLY by lazy { booleanPreferencesKey("mcp_localhost_only") }
    private val MCP_REQUIRE_AUTH by lazy { booleanPreferencesKey("mcp_require_auth") }
    private val LAST_AWS_PROFILES by lazy { stringPreferencesKey("last_aws_profiles") }
    private val LAST_GCP_PROJECTS by lazy { stringPreferencesKey("last_gcp_projects") }
    private val CLOSE_TAB_FOCUS by lazy { stringPreferencesKey("close_tab_focus") }
    private val TAB_STRIP_VISIBILITY by lazy { stringPreferencesKey("tab_strip_visibility") }
    private val SIDEBAR_COLLAPSED by lazy { booleanPreferencesKey("sidebar_collapsed") }
    private val DEMO_NODES_MIN by lazy { intPreferencesKey("demo_nodes_min") }
    private val DEMO_NODES_MAX by lazy { intPreferencesKey("demo_nodes_max") }
    private val DEMO_PODS_MIN by lazy { intPreferencesKey("demo_pods_min") }
    private val DEMO_PODS_MAX by lazy { intPreferencesKey("demo_pods_max") }
    private val EVENT_TRIAGE_PRESETS by lazy { stringPreferencesKey("event_triage_presets") }
    private val PINNED_RESOURCES by lazy { stringPreferencesKey("pinned_resources") }
    private val CLUSTER_COLOR_OVERRIDES by lazy { stringPreferencesKey("cluster_color_overrides") }
    private val TOPOLOGY_PACKET_ANIMATION_ENABLED by lazy { booleanPreferencesKey("topology_packet_animation_enabled") }
    private val TOPOLOGY_REFRESH_INTERVAL_SEC by lazy { intPreferencesKey("topology_refresh_interval_sec") }
    private val LOG_DRAWER_HEIGHT_DP by lazy { intPreferencesKey("log_drawer_height_dp") }
    private val MASK_SECRET_VALUES by lazy { booleanPreferencesKey("mask_secret_values") }
    private val RESTORE_SESSION_ON_LAUNCH by lazy { booleanPreferencesKey("restore_session_on_launch") }
    private val CAPTURE_DESTINATION_DIR by lazy { stringPreferencesKey("capture_destination_dir") }
    private val SIDEBAR_SECTIONS_EXPANDED by lazy { stringPreferencesKey("sidebar_sections_expanded") }
    private val STATS_PANELS_EXPANDED by lazy { stringPreferencesKey("stats_panels_expanded") }
    private val DETAIL_PANE_WIDTHS by lazy { stringPreferencesKey("detail_pane_widths") }
    private val PALETTE_RECENTS by lazy { stringPreferencesKey("palette_recents") }

    // ── Hot-cached StateFlows ─────────────────────────────────────────────────
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _mcpServerEnabled = MutableStateFlow(false)
    val mcpServerEnabled: StateFlow<Boolean> = _mcpServerEnabled.asStateFlow()

    private val _mcpServerPort = MutableStateFlow(3001)
    val mcpServerPort: StateFlow<Int> = _mcpServerPort.asStateFlow()

    private val _mcpLocalhostOnly = MutableStateFlow(true)
    val mcpLocalhostOnly: StateFlow<Boolean> = _mcpLocalhostOnly.asStateFlow()

    private val _mcpRequireAuth = MutableStateFlow(true)
    val mcpRequireAuth: StateFlow<Boolean> = _mcpRequireAuth.asStateFlow()

    private val _lastAwsProfiles = MutableStateFlow<List<String>>(emptyList())
    val lastAwsProfiles: StateFlow<List<String>> = _lastAwsProfiles.asStateFlow()

    private val _lastGcpProjects = MutableStateFlow<List<String>>(emptyList())
    val lastGcpProjects: StateFlow<List<String>> = _lastGcpProjects.asStateFlow()

    private val _closeTabFocus = MutableStateFlow(CloseTabFocus.LEFT_NEIGHBOR)
    val closeTabFocus: StateFlow<CloseTabFocus> = _closeTabFocus.asStateFlow()

    private val _tabStripVisibility = MutableStateFlow(TabStripVisibility.AUTO)
    val tabStripVisibility: StateFlow<TabStripVisibility> = _tabStripVisibility.asStateFlow()

    private val _sidebarCollapsed = MutableStateFlow(false)
    val sidebarCollapsed: StateFlow<Boolean> = _sidebarCollapsed.asStateFlow()

    private val _demoTargets = MutableStateFlow(
        DemoClusterSimulator.Targets(nodesMin = 30, nodesMax = 100, podsMin = 300, podsMax = 1000),
    )
    val demoTargets: StateFlow<DemoClusterSimulator.Targets> = _demoTargets.asStateFlow()

    private val _customPresets = MutableStateFlow<List<EventTriagePreset>>(emptyList())
    val customPresets: StateFlow<List<EventTriagePreset>> = _customPresets.asStateFlow()

    private val _pinnedResources = MutableStateFlow<Set<String>>(emptySet())
    val pinnedResources: StateFlow<Set<String>> = _pinnedResources.asStateFlow()

    private val _clusterColorOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val clusterColorOverrides: StateFlow<Map<String, String>> = _clusterColorOverrides.asStateFlow()

    private val _topologyPacketAnimationEnabled = MutableStateFlow(true)
    val topologyPacketAnimationEnabled: StateFlow<Boolean> = _topologyPacketAnimationEnabled.asStateFlow()

    // Auto-refresh interval for the topology screen. 0 = off; positive value = seconds.
    private val _topologyRefreshIntervalSec = MutableStateFlow(60)
    val topologyRefreshIntervalSec: StateFlow<Int> = _topologyRefreshIntervalSec.asStateFlow()

    private val _logDrawerHeightDp = MutableStateFlow(DEFAULT_LOG_DRAWER_HEIGHT_DP)
    val logDrawerHeightDp: StateFlow<Int> = _logDrawerHeightDp.asStateFlow()

    // Default ON — also the fail-safe initial value: readers (the YAML viewer) see
    // `true` (masked) during the window before the first DataStore emission, so a
    // Secret is never shown in the clear at startup. Do NOT "tidy" this to false.
    private val _maskSecretValues = MutableStateFlow(true)
    val maskSecretValues: StateFlow<Boolean> = _maskSecretValues.asStateFlow()

    // Whether launch rebuilds the last session's windows and tabs. Window
    // geometry is restored regardless; saving always runs so switching this
    // back on restores the latest state.
    private val _restoreSessionOnLaunch = MutableStateFlow(true)
    val restoreSessionOnLaunch: StateFlow<Boolean> = _restoreSessionOnLaunch.asStateFlow()

    /**
     * True once the first DataStore emission has seeded every flow above.
     * Session restore reads [restoreSessionOnLaunch] exactly once, at launch,
     * and must not act on the compile-time default.
     */
    private val _preferencesLoaded = MutableStateFlow(false)
    val preferencesLoaded: StateFlow<Boolean> = _preferencesLoaded.asStateFlow()

    private val _captureDestinationDir = MutableStateFlow(defaultCaptureDestinationDir())
    val captureDestinationDir: StateFlow<String> = _captureDestinationDir.asStateFlow()

    // Sidebar section expand overrides keyed by section title. Absent key =
    // the section's compile-time default (SidebarSection.defaultExpanded).
    private val _sidebarSectionsExpanded = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val sidebarSectionsExpanded: StateFlow<Map<String, Boolean>> = _sidebarSectionsExpanded.asStateFlow()

    // Stats-panel expand overrides keyed by STATS_PANEL_*. Absent key = expanded.
    private val _statsPanelsExpanded = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val statsPanelsExpanded: StateFlow<Map<String, Boolean>> = _statsPanelsExpanded.asStateFlow()

    // Detail-pane width per resource kind, in dp. Absent key = the host's default.
    private val _detailPaneWidths = MutableStateFlow<Map<String, Float>>(emptyMap())
    val detailPaneWidths: StateFlow<Map<String, Float>> = _detailPaneWidths.asStateFlow()

    // Command-palette recent-use ids, most-recent-first, capped at 8 (matches
    // CommandPalette's perCategoryCap so the Recent group is never silently
    // truncated). Ids are cluster-agnostic by design; resolution against the
    // current entry list happens in the palette, not here.
    private val _paletteRecents = MutableStateFlow<List<String>>(emptyList())
    val paletteRecents: StateFlow<List<String>> = _paletteRecents.asStateFlow()

    // Guards the one-shot seed of the four blobs above (two Boolean maps, one
    // Float map, one String list). Only ever touched from the single init
    // collector coroutine.
    private var mapBlobsSeeded = false

    // ── Seed all flows from DataStore on startup ──────────────────────────────
    init {
        ioScope.launch {
            // A DataStore read failure would otherwise end this collector with
            // preferencesLoaded still false, and launch-time readers would wait
            // out their timeout on every start; flag "loaded" with the defaults.
            dataStore.data.catch { _preferencesLoaded.value = true }.collect { p ->
                _themeMode.value = p[THEME_MODE]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM
                _mcpServerEnabled.value = p[MCP_SERVER_ENABLED] ?: false
                _mcpServerPort.value = p[MCP_SERVER_PORT] ?: 3001
                _mcpLocalhostOnly.value = p[MCP_LOCALHOST_ONLY] ?: true
                _mcpRequireAuth.value = p[MCP_REQUIRE_AUTH] ?: true
                _lastAwsProfiles.value = p[LAST_AWS_PROFILES]
                    ?.split(",")?.filter { it.isNotBlank() }
                    ?: emptyList()
                _lastGcpProjects.value = p[LAST_GCP_PROJECTS]
                    ?.split(",")?.filter { it.isNotBlank() }
                    ?: emptyList()
                _closeTabFocus.value = decodeCloseTabFocus(p[CLOSE_TAB_FOCUS])
                _tabStripVisibility.value = decodeTabStripVisibility(p[TAB_STRIP_VISIBILITY])
                _sidebarCollapsed.value = p[SIDEBAR_COLLAPSED] ?: false
                _demoTargets.value = DemoClusterSimulator.Targets(
                    nodesMin = p[DEMO_NODES_MIN] ?: 30,
                    nodesMax = p[DEMO_NODES_MAX] ?: 100,
                    podsMin = p[DEMO_PODS_MIN] ?: 300,
                    podsMax = p[DEMO_PODS_MAX] ?: 1000,
                )
                _customPresets.value = decodePresets(p[EVENT_TRIAGE_PRESETS])
                _pinnedResources.value = p[PINNED_RESOURCES]
                    ?.split(",")?.filter { it.isNotBlank() }?.toSet()
                    ?: emptySet()
                _clusterColorOverrides.value = decodeColorOverrides(p[CLUSTER_COLOR_OVERRIDES])
                _topologyPacketAnimationEnabled.value = p[TOPOLOGY_PACKET_ANIMATION_ENABLED] ?: true
                _topologyRefreshIntervalSec.value = p[TOPOLOGY_REFRESH_INTERVAL_SEC] ?: 60
                _logDrawerHeightDp.value = (p[LOG_DRAWER_HEIGHT_DP] ?: DEFAULT_LOG_DRAWER_HEIGHT_DP)
                    .coerceIn(MIN_LOG_DRAWER_HEIGHT_DP, MAX_LOG_DRAWER_HEIGHT_DP)
                _maskSecretValues.value = p[MASK_SECRET_VALUES] ?: true
                _restoreSessionOnLaunch.value = p[RESTORE_SESSION_ON_LAUNCH] ?: true
                _captureDestinationDir.value = p[CAPTURE_DESTINATION_DIR] ?: defaultCaptureDestinationDir()
                // Seed ONCE. This collector re-fires on every DataStore commit
                // (including unrelated keys), and setSidebarSectionExpanded /
                // setStatsPanelExpanded write the flow synchronously while
                // persisting from an unordered ioScope.launch — re-seeding on
                // later emissions would overwrite a just-toggled value with the
                // stale persisted one (visible flip-back). After the first
                // emission the in-memory flow is authoritative; the process has
                // exactly one DataStore, so nothing else can change it. The
                // in-memory map is merged on top of the persisted one so a toggle
                // that races ahead of this first emission is not thrown away.
                if (!mapBlobsSeeded) {
                    mapBlobsSeeded = true
                    _sidebarSectionsExpanded.value =
                        BoolMapCodec.decode(p[SIDEBAR_SECTIONS_EXPANDED]) + _sidebarSectionsExpanded.value
                    _statsPanelsExpanded.value =
                        BoolMapCodec.decode(p[STATS_PANELS_EXPANDED]) + _statsPanelsExpanded.value
                    _detailPaneWidths.value =
                        FloatMapCodec.decode(p[DETAIL_PANE_WIDTHS]) + _detailPaneWidths.value
                    // Concatenation, not a map merge: the in-memory list (a
                    // race-ahead recordPaletteUse) goes first so it keeps
                    // recency priority over the persisted list.
                    val decoded = StringListCodec.decode(p[PALETTE_RECENTS])
                    _paletteRecents.value = (_paletteRecents.value + decoded).distinct().take(8)
                }
                _preferencesLoaded.value = true
            }
        }
    }

    // ── Setters ───────────────────────────────────────────────────────────────
    fun setThemeMode(value: ThemeMode) {
        _themeMode.value = value
        ioScope.launch { dataStore.edit { it[THEME_MODE] = value.name } }
    }

    fun setMcpServerEnabled(value: Boolean) {
        _mcpServerEnabled.value = value
        ioScope.launch { dataStore.edit { it[MCP_SERVER_ENABLED] = value } }
    }

    fun setMcpServerPort(value: Int) {
        _mcpServerPort.value = value
        ioScope.launch { dataStore.edit { it[MCP_SERVER_PORT] = value } }
    }

    fun setMcpLocalhostOnly(value: Boolean) {
        _mcpLocalhostOnly.value = value
        ioScope.launch { dataStore.edit { it[MCP_LOCALHOST_ONLY] = value } }
    }

    fun setMcpRequireAuth(value: Boolean) {
        _mcpRequireAuth.value = value
        ioScope.launch { dataStore.edit { it[MCP_REQUIRE_AUTH] = value } }
    }

    fun setLastAwsProfiles(value: List<String>) {
        val cleaned = value.filter { it.isNotBlank() }.distinct()
        _lastAwsProfiles.value = cleaned
        ioScope.launch {
            dataStore.edit {
                if (cleaned.isEmpty()) it.remove(LAST_AWS_PROFILES) else it[LAST_AWS_PROFILES] = cleaned.joinToString(",")
            }
        }
    }

    fun setLastGcpProjects(value: List<String>) {
        val cleaned = value.filter { it.isNotBlank() }.distinct()
        _lastGcpProjects.value = cleaned
        ioScope.launch {
            dataStore.edit {
                if (cleaned.isEmpty()) it.remove(LAST_GCP_PROJECTS) else it[LAST_GCP_PROJECTS] = cleaned.joinToString(",")
            }
        }
    }

    fun setTopologyPacketAnimationEnabled(value: Boolean) {
        _topologyPacketAnimationEnabled.value = value
        ioScope.launch { dataStore.edit { it[TOPOLOGY_PACKET_ANIMATION_ENABLED] = value } }
    }

    fun setTopologyRefreshIntervalSec(value: Int) {
        val clamped = value.coerceAtLeast(0)
        _topologyRefreshIntervalSec.value = clamped
        ioScope.launch { dataStore.edit { it[TOPOLOGY_REFRESH_INTERVAL_SEC] = clamped } }
    }

    fun setLogDrawerHeightDp(value: Int) {
        val clamped = value.coerceIn(MIN_LOG_DRAWER_HEIGHT_DP, MAX_LOG_DRAWER_HEIGHT_DP)
        if (_logDrawerHeightDp.value == clamped) return
        _logDrawerHeightDp.value = clamped
        ioScope.launch { dataStore.edit { it[LOG_DRAWER_HEIGHT_DP] = clamped } }
    }

    fun setCloseTabFocus(value: CloseTabFocus) {
        _closeTabFocus.value = value
        ioScope.launch { dataStore.edit { it[CLOSE_TAB_FOCUS] = value.name } }
    }

    fun setTabStripVisibility(value: TabStripVisibility) {
        _tabStripVisibility.value = value
        ioScope.launch { dataStore.edit { it[TAB_STRIP_VISIBILITY] = value.name } }
    }

    fun setSidebarCollapsed(value: Boolean) {
        _sidebarCollapsed.value = value
        ioScope.launch { dataStore.edit { it[SIDEBAR_COLLAPSED] = value } }
    }

    fun setMaskSecretValues(value: Boolean) {
        _maskSecretValues.value = value
        ioScope.launch { dataStore.edit { it[MASK_SECRET_VALUES] = value } }
    }

    fun setRestoreSessionOnLaunch(value: Boolean) {
        _restoreSessionOnLaunch.value = value
        ioScope.launch { dataStore.edit { it[RESTORE_SESSION_ON_LAUNCH] = value } }
    }

    fun setCaptureDestinationDir(value: String) {
        if (_captureDestinationDir.value == value) return
        _captureDestinationDir.value = value
        ioScope.launch { dataStore.edit { it[CAPTURE_DESTINATION_DIR] = value } }
    }

    fun setSidebarSectionExpanded(title: String, expanded: Boolean) {
        _sidebarSectionsExpanded.value = _sidebarSectionsExpanded.value + (title to expanded)
        ioScope.launch {
            dataStore.edit { prefs ->
                val current = BoolMapCodec.decode(prefs[SIDEBAR_SECTIONS_EXPANDED])
                prefs[SIDEBAR_SECTIONS_EXPANDED] = BoolMapCodec.encode(current + (title to expanded))
            }
        }
    }

    fun setStatsPanelExpanded(key: String, expanded: Boolean) {
        _statsPanelsExpanded.value = _statsPanelsExpanded.value + (key to expanded)
        ioScope.launch {
            dataStore.edit { prefs ->
                val current = BoolMapCodec.decode(prefs[STATS_PANELS_EXPANDED])
                prefs[STATS_PANELS_EXPANDED] = BoolMapCodec.encode(current + (key to expanded))
            }
        }
    }

    /** Remembers the detail-pane width the user dragged to for [kind]; the host calls it once per drag, on release. */
    fun setDetailPaneWidth(kind: String, widthDp: Float) {
        if (_detailPaneWidths.value[kind] == widthDp) return
        _detailPaneWidths.value = _detailPaneWidths.value + (kind to widthDp)
        ioScope.launch {
            dataStore.edit { prefs ->
                val current = FloatMapCodec.decode(prefs[DETAIL_PANE_WIDTHS])
                prefs[DETAIL_PANE_WIDTHS] = FloatMapCodec.encode(current + (kind to widthDp))
            }
        }
    }

    /** Records a palette activation: moves [id] to the front, dedupes, caps at 8. One `dataStore.edit` — a click path, not a keystroke path. */
    fun recordPaletteUse(id: String) {
        val updated = (listOf(id) + _paletteRecents.value).distinct().take(8)
        _paletteRecents.value = updated
        ioScope.launch { dataStore.edit { it[PALETTE_RECENTS] = StringListCodec.encode(updated) } }
    }

    fun setDemoTargets(value: DemoClusterSimulator.Targets) {
        _demoTargets.value = value
        ioScope.launch {
            dataStore.edit {
                it[DEMO_NODES_MIN] = value.nodesMin
                it[DEMO_NODES_MAX] = value.nodesMax
                it[DEMO_PODS_MIN] = value.podsMin
                it[DEMO_PODS_MAX] = value.podsMax
            }
        }
    }

    fun setCustomPresets(value: List<EventTriagePreset>) {
        _customPresets.value = value
        ioScope.launch { dataStore.edit { it[EVENT_TRIAGE_PRESETS] = json.encodeToString(value) } }
    }

    suspend fun togglePinned(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[PINNED_RESOURCES]
                ?.split(",")?.filter { it.isNotBlank() }?.toMutableSet()
                ?: mutableSetOf()
            if (id in current) current.remove(id) else current.add(id)
            prefs[PINNED_RESOURCES] = current.joinToString(",")
        }
    }

    suspend fun setClusterColor(context: String, hex: String) {
        dataStore.edit { prefs ->
            val current = decodeColorOverrides(prefs[CLUSTER_COLOR_OVERRIDES]).toMutableMap()
            current[context] = hex
            prefs[CLUSTER_COLOR_OVERRIDES] = json.encodeToString(current)
        }
    }

    suspend fun clearClusterColor(context: String) {
        dataStore.edit { prefs ->
            val current = decodeColorOverrides(prefs[CLUSTER_COLOR_OVERRIDES]).toMutableMap()
            current.remove(context)
            prefs[CLUSTER_COLOR_OVERRIDES] = json.encodeToString(current)
        }
    }

    // ── Decoders ──────────────────────────────────────────────────────────────
    private fun decodeCloseTabFocus(raw: String?): CloseTabFocus = raw?.let { runCatching { CloseTabFocus.valueOf(it) }.getOrNull() } ?: CloseTabFocus.LEFT_NEIGHBOR

    private fun decodeTabStripVisibility(raw: String?): TabStripVisibility = raw?.let { runCatching { TabStripVisibility.valueOf(it) }.getOrNull() } ?: TabStripVisibility.AUTO

    private fun decodePresets(raw: String?): List<EventTriagePreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<EventTriagePreset>>(raw)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun decodeColorOverrides(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun defaultCaptureDestinationDir(): String {
        val home = System.getProperty("user.home")
        val downloads = java.io.File(home, "Downloads")
        return if (downloads.exists()) downloads.absolutePath else home
    }
}
