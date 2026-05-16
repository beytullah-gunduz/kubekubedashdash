package com.kubekubedashdash.ui.screens.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.mcp.McpServerManager
import com.kubekubedashdash.model.CloseTabFocus
import com.kubekubedashdash.model.TabStripVisibility
import com.kubekubedashdash.util.DemoClusterSimulator
import com.kubekubedashdash.util.MockClusterProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsScreenViewModel : ViewModel() {
    val themeMode: ThemeMode
        get() = ThemeManager.mode

    fun setThemeMode(mode: ThemeMode) {
        ThemeManager.setMode(mode)
    }

    // Audit A5: single source of truth. PreferenceRepository already exposes
    // these as DataStore-backed StateFlows; the VM previously held Compose
    // `mutableStateOf` copies seeded once in init, which made the VM
    // untestable without the Compose runtime and could desync from the
    // persisted prefs. Delegate straight through — setters write the repo
    // (synchronously updating its in-memory flow), the repo's flow drives
    // the UI.
    val isMcpServerEnabled: StateFlow<Boolean> = PreferenceRepository.mcpServerEnabled
    val mcpServerPort: StateFlow<Int> = PreferenceRepository.mcpServerPort
    val mcpLocalhostOnly: StateFlow<Boolean> = PreferenceRepository.mcpLocalhostOnly
    val mcpRequireAuth: StateFlow<Boolean> = PreferenceRepository.mcpRequireAuth

    // Runtime-only: McpServerManager regenerates the token on every start and
    // exposes no flow, so this is genuine VM-owned state — a StateFlow for
    // consistency with the rest of this VM, not Compose state.
    private val _mcpBearerToken = MutableStateFlow(McpServerManager.bearerToken)
    val mcpBearerToken: StateFlow<String?> = _mcpBearerToken.asStateFlow()

    fun toggleMcpServer(enabled: Boolean) {
        // Synchronous: setMcpServerEnabled updates the in-memory flow
        // instantly (persistence is fire-and-forget), so the switch reflects
        // immediately; only the blocking server start/stop goes to IO.
        PreferenceRepository.setMcpServerEnabled(enabled)
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                McpServerManager.start(mcpServerPort.value)
                _mcpBearerToken.value = McpServerManager.bearerToken
            } else {
                McpServerManager.stop()
                _mcpBearerToken.value = null
            }
        }
    }

    fun updateMcpServerPort(port: Int) {
        PreferenceRepository.setMcpServerPort(port)
        viewModelScope.launch(Dispatchers.IO) {
            if (isMcpServerEnabled.value) {
                McpServerManager.start(port)
                _mcpBearerToken.value = McpServerManager.bearerToken
            }
        }
    }

    fun updateMcpLocalhostOnly(v: Boolean) {
        PreferenceRepository.setMcpLocalhostOnly(v)
    }

    fun updateMcpRequireAuth(v: Boolean) {
        PreferenceRepository.setMcpRequireAuth(v)
    }

    private val _closeTabFocus = MutableStateFlow(PreferenceRepository.closeTabFocus.value)
    val closeTabFocus: StateFlow<CloseTabFocus> = _closeTabFocus.asStateFlow()

    fun setCloseTabFocus(value: CloseTabFocus) {
        PreferenceRepository.setCloseTabFocus(value)
        _closeTabFocus.value = value
    }

    private val _tabStripVisibility = MutableStateFlow(PreferenceRepository.tabStripVisibility.value)
    val tabStripVisibility: StateFlow<TabStripVisibility> = _tabStripVisibility.asStateFlow()

    fun setTabStripVisibility(value: TabStripVisibility) {
        PreferenceRepository.setTabStripVisibility(value)
        _tabStripVisibility.value = value
    }

    // ── Demo cluster simulator ─────────────────────────────────────────────────
    //
    // With per-tab mock instances, controls in this panel apply *globally* to every
    // live simulator. Pause toggles all of them; target sliders update all of them
    // and persist as the default for newly-minted instances. The "any paused" /
    // "any running" derivation is intentional: it surfaces a non-default state on
    // even one instance so the Settings switch stays consistent with what the user
    // sees in any open tab.

    val mockIsRunning: StateFlow<Boolean> = MockClusterProvider.isRunning
    val mockConnectedTabs: StateFlow<Int> = MockClusterProvider.connectedTabCount

    // Single source of truth for mock targets. Seeded from preferences so the Settings
    // screen shows the saved values even before the simulator boots, and updated
    // synchronously by setMockTargets so slider drags reflect immediately.
    private val _mockTargets = MutableStateFlow(PreferenceRepository.demoTargets.value)
    val mockTargets: StateFlow<DemoClusterSimulator.Targets> = _mockTargets.asStateFlow()

    private val _mockPaused = MutableStateFlow(false)
    val mockPaused: StateFlow<Boolean> = _mockPaused.asStateFlow()

    fun setMockPaused(p: Boolean) {
        _mockPaused.value = p
        MockClusterProvider.simulators().forEach { it.setPaused(p) }
    }

    fun setMockTargets(t: DemoClusterSimulator.Targets) {
        _mockTargets.value = t
        MockClusterProvider.simulators().forEach { it.setTargets(t) }
        PreferenceRepository.setDemoTargets(t)
    }

    fun stopAllMockResources() {
        MockClusterProvider.simulators().forEach { it.stopAll() }
    }

    fun killMockServer() {
        MockClusterProvider.forceShutdown()
    }

    // ── Cluster color overrides ────────────────────────────────────────────────

    val clusterColorOverrides: StateFlow<Map<String, String>> = PreferenceRepository.clusterColorOverrides

    fun setClusterColor(context: String, hex: String) {
        viewModelScope.launch { PreferenceRepository.setClusterColor(context, hex) }
    }

    fun clearClusterColor(context: String) {
        viewModelScope.launch { PreferenceRepository.clearClusterColor(context) }
    }

    init {
        // State now lives in PreferenceRepository's flows; init only needs to
        // honor the persisted "MCP enabled" intent on startup and seed the
        // runtime bearer token.
        viewModelScope.launch(Dispatchers.IO) {
            if (PreferenceRepository.mcpServerEnabled.value && !McpServerManager.isRunning) {
                McpServerManager.start(PreferenceRepository.mcpServerPort.value)
            }
            _mcpBearerToken.value = McpServerManager.bearerToken
        }
    }
}
