package com.kubekubedashdash.ui.screens.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.mcp.McpServerManager
import com.kubekubedashdash.model.CloseTabFocus
import com.kubekubedashdash.model.TabStripVisibility
import com.kubekubedashdash.ui.components.TableDensity
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

    // Single source of truth — delegate straight to PreferenceRepository's
    // DataStore-backed flows (same A5 rationale: the old local
    // MutableStateFlow mirror was seeded once from `.value` and could go
    // stale if the prefs reloaded). Setters update the repo's in-memory flow
    // synchronously, so the UI still reacts immediately.
    val closeTabFocus: StateFlow<CloseTabFocus> = PreferenceRepository.closeTabFocus

    fun setCloseTabFocus(value: CloseTabFocus) {
        PreferenceRepository.setCloseTabFocus(value)
    }

    val tabStripVisibility: StateFlow<TabStripVisibility> = PreferenceRepository.tabStripVisibility

    fun setTabStripVisibility(value: TabStripVisibility) {
        PreferenceRepository.setTabStripVisibility(value)
    }

    val maskSecretValues: StateFlow<Boolean> = PreferenceRepository.maskSecretValues

    fun setMaskSecretValues(value: Boolean) {
        PreferenceRepository.setMaskSecretValues(value)
    }

    val restoreSessionOnLaunch: StateFlow<Boolean> = PreferenceRepository.restoreSessionOnLaunch

    fun setRestoreSessionOnLaunch(value: Boolean) {
        PreferenceRepository.setRestoreSessionOnLaunch(value)
    }

    val tableDensity: StateFlow<TableDensity> = PreferenceRepository.tableDensity

    fun setTableDensity(value: TableDensity) {
        PreferenceRepository.setTableDensity(value)
    }

    val topologyRefreshIntervalSec: StateFlow<Int> = PreferenceRepository.topologyRefreshIntervalSec

    fun setTopologyRefreshIntervalSec(value: Int) {
        PreferenceRepository.setTopologyRefreshIntervalSec(value)
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

    // ── Default namespace per cluster ──────────────────────────────────────────

    val defaultNamespaceByContext: StateFlow<Map<String, String>> = PreferenceRepository.defaultNamespaceByContext

    fun setDefaultNamespace(context: String, namespace: String) {
        viewModelScope.launch { PreferenceRepository.setDefaultNamespace(context, namespace) }
    }

    fun clearDefaultNamespace(context: String) {
        viewModelScope.launch { PreferenceRepository.clearDefaultNamespace(context) }
    }

    init {
        // State now lives in PreferenceRepository's flows; init only needs to
        // honor the persisted "MCP enabled" intent on startup and seed the
        // runtime bearer token.
        viewModelScope.launch(Dispatchers.IO) {
            if (PreferenceRepository.mcpServerEnabled.value) {
                McpServerManager.startIfNotRunning(PreferenceRepository.mcpServerPort.value)
            }
            _mcpBearerToken.value = McpServerManager.bearerToken
        }
    }
}
