package com.kubekubedashdash.ui.screens.settings.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsScreenViewModel : ViewModel() {
    val themeMode: ThemeMode
        get() = ThemeManager.mode

    fun setThemeMode(mode: ThemeMode) {
        ThemeManager.setMode(mode)
    }

    var isMcpServerEnabled: Boolean by mutableStateOf(McpServerManager.isRunning)
        private set

    var mcpServerPort: Int by mutableStateOf(McpServerManager.DEFAULT_PORT)
        private set

    fun toggleMcpServer(enabled: Boolean) {
        isMcpServerEnabled = enabled
        viewModelScope.launch(Dispatchers.IO) {
            PreferenceRepository.mcpServerEnabled = enabled
            if (enabled) {
                McpServerManager.start(mcpServerPort)
            } else {
                McpServerManager.stop()
            }
        }
    }

    fun updateMcpServerPort(port: Int) {
        mcpServerPort = port
        viewModelScope.launch(Dispatchers.IO) {
            PreferenceRepository.mcpServerPort = port
            if (isMcpServerEnabled) {
                McpServerManager.start(port)
            }
        }
    }

    private val _closeTabFocus = MutableStateFlow(PreferenceRepository.closeTabFocus)
    val closeTabFocus: StateFlow<CloseTabFocus> = _closeTabFocus.asStateFlow()

    fun setCloseTabFocus(value: CloseTabFocus) {
        PreferenceRepository.closeTabFocus = value
        _closeTabFocus.value = value
    }

    private val _tabStripVisibility = MutableStateFlow(PreferenceRepository.tabStripVisibility)
    val tabStripVisibility: StateFlow<TabStripVisibility> = _tabStripVisibility.asStateFlow()

    fun setTabStripVisibility(value: TabStripVisibility) {
        PreferenceRepository.tabStripVisibility = value
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
    private val _mockTargets = MutableStateFlow(PreferenceRepository.demoTargets)
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
        viewModelScope.launch(Dispatchers.IO) { PreferenceRepository.demoTargets = t }
    }

    fun stopAllMockResources() {
        MockClusterProvider.simulators().forEach { it.stopAll() }
    }

    fun killMockServer() {
        MockClusterProvider.forceShutdown()
    }

    // ── Cluster color overrides ────────────────────────────────────────────────

    val clusterColorOverrides: StateFlow<Map<String, String>> =
        PreferenceRepository.clusterColorOverrides()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setClusterColor(context: String, hex: String) {
        viewModelScope.launch { PreferenceRepository.setClusterColor(context, hex) }
    }

    fun clearClusterColor(context: String) {
        viewModelScope.launch { PreferenceRepository.clearClusterColor(context) }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            mcpServerPort = PreferenceRepository.mcpServerPort
            val enabled = PreferenceRepository.mcpServerEnabled
            if (enabled && !McpServerManager.isRunning) {
                McpServerManager.start(mcpServerPort)
            }
            isMcpServerEnabled = enabled
        }
    }
}
