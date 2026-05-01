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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

object PreferenceRepository {
    private val dataStore: DataStore<Preferences> by lazy { dataStorePreferencesInstance }

    private val THEME_MODE by lazy { stringPreferencesKey("theme_mode") }
    private val MCP_SERVER_ENABLED by lazy { booleanPreferencesKey("mcp_server_enabled") }
    private val MCP_SERVER_PORT by lazy { intPreferencesKey("mcp_server_port") }
    private val LAST_AWS_PROFILE by lazy { stringPreferencesKey("last_aws_profile") }
    private val CLOSE_TAB_FOCUS by lazy { stringPreferencesKey("close_tab_focus") }

    var themeMode: ThemeMode
        get() = runBlocking {
            val name = dataStore.data.firstOrNull()?.get(THEME_MODE) ?: return@runBlocking ThemeMode.SYSTEM
            runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
        }
        set(value) {
            runBlocking {
                dataStore.edit {
                    it[THEME_MODE] = value.name
                }
            }
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

    var lastAwsProfile: String?
        get() = runBlocking { dataStore.data.firstOrNull()?.get(LAST_AWS_PROFILE) }
        set(value) {
            runBlocking {
                dataStore.edit {
                    if (value.isNullOrBlank()) it.remove(LAST_AWS_PROFILE) else it[LAST_AWS_PROFILE] = value
                }
            }
        }

    var closeTabFocus: CloseTabFocus
        get() = runBlocking {
            val raw = dataStore.data.firstOrNull()?.get(CLOSE_TAB_FOCUS)
            decodeCloseTabFocus(raw)
        }
        set(value) {
            runBlocking {
                dataStore.edit {
                    it[CLOSE_TAB_FOCUS] = value.name
                }
            }
        }

    fun closeTabFocus(): Flow<CloseTabFocus> = dataStore.data.map {
        decodeCloseTabFocus(it[CLOSE_TAB_FOCUS])
    }

    private fun decodeCloseTabFocus(raw: String?): CloseTabFocus = raw?.let { runCatching { CloseTabFocus.valueOf(it) }.getOrNull() }
        ?: CloseTabFocus.LEFT_NEIGHBOR
}
