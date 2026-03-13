package com.kubekubedashdash.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.kubekubedashdash.data.datastore.dataStorePreferencesInstance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

object PreferenceRepository {
    private val dataStore: DataStore<Preferences> by lazy { dataStorePreferencesInstance }

    private val DARK_THEME by lazy { booleanPreferencesKey("dark_theme") }
    private val MCP_SERVER_ENABLED by lazy { booleanPreferencesKey("mcp_server_enabled") }
    private val MCP_SERVER_PORT by lazy { intPreferencesKey("mcp_server_port") }

    var darkTheme: Boolean
        get() = runBlocking { dataStore.data.firstOrNull()?.get(DARK_THEME) ?: true }
        set(value) {
            runBlocking {
                dataStore.edit {
                    it[DARK_THEME] = value
                }
            }
        }

    fun darkTheme(): Flow<Boolean> = dataStore.data.map {
        it[DARK_THEME] ?: true
    }

    var mcpServerEnabled: Boolean
        get() = runBlocking { dataStore.data.firstOrNull()?.get(MCP_SERVER_ENABLED) ?: false }
        set(value) {
            runBlocking {
                dataStore.edit {
                    it[MCP_SERVER_ENABLED] = value
                }
            }
        }

    fun mcpServerEnabled(): Flow<Boolean> = dataStore.data.map {
        it[MCP_SERVER_ENABLED] ?: false
    }

    var mcpServerPort: Int
        get() = runBlocking { dataStore.data.firstOrNull()?.get(MCP_SERVER_PORT) ?: 3001 }
        set(value) {
            runBlocking {
                dataStore.edit {
                    it[MCP_SERVER_PORT] = value
                }
            }
        }
}
