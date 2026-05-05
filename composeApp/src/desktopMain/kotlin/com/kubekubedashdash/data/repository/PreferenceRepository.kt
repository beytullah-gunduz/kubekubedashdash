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
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PreferenceRepository {
    private val dataStore: DataStore<Preferences> by lazy { dataStorePreferencesInstance }
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    // ── Preference keys ───────────────────────────────────────────────────────
    private val THEME_MODE by lazy { stringPreferencesKey("theme_mode") }
    private val MCP_SERVER_ENABLED by lazy { booleanPreferencesKey("mcp_server_enabled") }
    private val MCP_SERVER_PORT by lazy { intPreferencesKey("mcp_server_port") }
    private val MCP_LOCALHOST_ONLY by lazy { booleanPreferencesKey("mcp_localhost_only") }
    private val MCP_REQUIRE_AUTH by lazy { booleanPreferencesKey("mcp_require_auth") }
    private val LAST_AWS_PROFILE by lazy { stringPreferencesKey("last_aws_profile") }
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

    private val _lastAwsProfile = MutableStateFlow<String?>(null)
    val lastAwsProfile: StateFlow<String?> = _lastAwsProfile.asStateFlow()

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

    // ── Seed all flows from DataStore on startup ──────────────────────────────
    init {
        ioScope.launch {
            dataStore.data.collect { p ->
                _themeMode.value = p[THEME_MODE]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM
                _mcpServerEnabled.value = p[MCP_SERVER_ENABLED] ?: false
                _mcpServerPort.value = p[MCP_SERVER_PORT] ?: 3001
                _mcpLocalhostOnly.value = p[MCP_LOCALHOST_ONLY] ?: true
                _mcpRequireAuth.value = p[MCP_REQUIRE_AUTH] ?: true
                _lastAwsProfile.value = p[LAST_AWS_PROFILE]
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

    fun setLastAwsProfile(value: String?) {
        _lastAwsProfile.value = value
        ioScope.launch {
            dataStore.edit {
                if (value.isNullOrBlank()) it.remove(LAST_AWS_PROFILE) else it[LAST_AWS_PROFILE] = value
            }
        }
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
}
