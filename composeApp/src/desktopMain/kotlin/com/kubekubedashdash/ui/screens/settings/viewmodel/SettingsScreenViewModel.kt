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

    // ── Demo cluster simulator ─────────────────────────────────────────────────

    val mockIsRunning: StateFlow<Boolean> = MockClusterProvider.isRunning
    val mockConnectedTabs: StateFlow<Int> = MockClusterProvider.connectedTabCount

    val mockTargets: StateFlow<DemoClusterSimulator.Targets>
        get() = MockClusterProvider.simulatorOrNull()?.targets ?: _defaultTargets
    val mockPaused: StateFlow<Boolean>
        get() = MockClusterProvider.simulatorOrNull()?.paused ?: MutableStateFlow(false)

    private val _defaultTargets = MutableStateFlow(DemoClusterSimulator.Targets())

    fun setMockPaused(p: Boolean) {
        MockClusterProvider.simulatorOrNull()?.setPaused(p)
    }

    fun setMockTargets(t: DemoClusterSimulator.Targets) {
        MockClusterProvider.simulatorOrNull()?.setTargets(t)
        viewModelScope.launch(Dispatchers.IO) { PreferenceRepository.demoTargets = t }
    }

    fun stopAllMockResources() {
        MockClusterProvider.simulatorOrNull()?.stopAll()
    }

    fun killMockServer() {
        MockClusterProvider.forceShutdown()
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
