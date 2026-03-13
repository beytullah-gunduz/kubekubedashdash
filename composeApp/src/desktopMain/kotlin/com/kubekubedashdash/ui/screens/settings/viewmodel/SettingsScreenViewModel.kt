package com.kubekubedashdash.ui.screens.settings.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.mcp.McpServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsScreenViewModel : ViewModel() {
    val isDarkTheme: Boolean
        get() = ThemeManager.isDarkTheme

    fun setDarkTheme(dark: Boolean) {
        ThemeManager.isDarkTheme = dark
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
