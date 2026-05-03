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
import com.kubekubedashdash.util.DemoClusterSimulator
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
    private val TAB_STRIP_VISIBILITY by lazy { stringPreferencesKey("tab_strip_visibility") }
    private val SIDEBAR_COLLAPSED by lazy { booleanPreferencesKey("sidebar_collapsed") }
    private val DEMO_NODES_MIN by lazy { intPreferencesKey("demo_nodes_min") }
    private val DEMO_NODES_MAX by lazy { intPreferencesKey("demo_nodes_max") }
    private val DEMO_PODS_MIN by lazy { intPreferencesKey("demo_pods_min") }
    private val DEMO_PODS_MAX by lazy { intPreferencesKey("demo_pods_max") }

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

    var tabStripVisibility: TabStripVisibility
        get() = runBlocking {
            val raw = dataStore.data.firstOrNull()?.get(TAB_STRIP_VISIBILITY)
            decodeTabStripVisibility(raw)
        }
        set(value) {
            runBlocking {
                dataStore.edit {
                    it[TAB_STRIP_VISIBILITY] = value.name
                }
            }
        }

    fun tabStripVisibility(): Flow<TabStripVisibility> = dataStore.data.map {
        decodeTabStripVisibility(it[TAB_STRIP_VISIBILITY])
    }

    private fun decodeTabStripVisibility(raw: String?): TabStripVisibility = raw?.let { runCatching { TabStripVisibility.valueOf(it) }.getOrNull() }
        ?: TabStripVisibility.AUTO

    var sidebarCollapsed: Boolean
        get() = runBlocking { dataStore.data.firstOrNull()?.get(SIDEBAR_COLLAPSED) ?: false }
        set(value) {
            runBlocking {
                dataStore.edit {
                    it[SIDEBAR_COLLAPSED] = value
                }
            }
        }

    fun sidebarCollapsed(): Flow<Boolean> = dataStore.data.map {
        it[SIDEBAR_COLLAPSED] ?: false
    }

    var demoTargets: DemoClusterSimulator.Targets
        get() = runBlocking {
            val p = dataStore.data.firstOrNull()
            DemoClusterSimulator.Targets(
                nodesMin = p?.get(DEMO_NODES_MIN) ?: 30,
                nodesMax = p?.get(DEMO_NODES_MAX) ?: 100,
                podsMin = p?.get(DEMO_PODS_MIN) ?: 300,
                podsMax = p?.get(DEMO_PODS_MAX) ?: 1000,
            )
        }
        set(value) {
            runBlocking {
                dataStore.edit {
                    it[DEMO_NODES_MIN] = value.nodesMin
                    it[DEMO_NODES_MAX] = value.nodesMax
                    it[DEMO_PODS_MIN] = value.podsMin
                    it[DEMO_PODS_MAX] = value.podsMax
                }
            }
        }
}
