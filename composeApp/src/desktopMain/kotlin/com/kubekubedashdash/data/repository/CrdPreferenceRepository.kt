package com.kubekubedashdash.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubekubedashdash.data.datastore.dataStorePreferencesInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-cluster pin/hide preferences for custom resource entries in the
 * sidebar. Stored as a single JSON `Map<context, List<crdKey>>` blob in the
 * shared DataStore. Each `crdKey` is `"${group}/${kind}"` so version bumps
 * (v1beta1 → v1) don't drop the user's preference.
 *
 * Pin and hide are mutually exclusive: pinning auto-unhides; hiding
 * auto-unpins.
 */
object CrdPreferenceRepository {

    private val dataStore: DataStore<Preferences> by lazy { dataStorePreferencesInstance }
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private val CRD_PINNED by lazy { stringPreferencesKey("crd_pinned_per_context") }
    private val CRD_HIDDEN by lazy { stringPreferencesKey("crd_hidden_per_context") }

    private val _pinnedByContext = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val pinnedByContext: StateFlow<Map<String, Set<String>>> = _pinnedByContext.asStateFlow()

    private val _hiddenByContext = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val hiddenByContext: StateFlow<Map<String, Set<String>>> = _hiddenByContext.asStateFlow()

    init {
        ioScope.launch {
            dataStore.data.collect { p ->
                _pinnedByContext.value = decode(p[CRD_PINNED])
                _hiddenByContext.value = decode(p[CRD_HIDDEN])
            }
        }
    }

    fun pinnedFor(context: String): Set<String> = _pinnedByContext.value[context].orEmpty()
    fun hiddenFor(context: String): Set<String> = _hiddenByContext.value[context].orEmpty()

    fun togglePinned(context: String, crdKey: String) {
        ioScope.launch {
            dataStore.edit { prefs ->
                val pinned = decode(prefs[CRD_PINNED]).toMutableMap()
                val hidden = decode(prefs[CRD_HIDDEN]).toMutableMap()
                val pinnedHere = pinned[context]?.toMutableSet() ?: mutableSetOf()
                val hiddenHere = hidden[context]?.toMutableSet() ?: mutableSetOf()
                if (crdKey in pinnedHere) {
                    pinnedHere.remove(crdKey)
                } else {
                    pinnedHere.add(crdKey)
                    hiddenHere.remove(crdKey)
                }
                writeBack(prefs, pinned, context, pinnedHere, CRD_PINNED)
                writeBack(prefs, hidden, context, hiddenHere, CRD_HIDDEN)
            }
        }
    }

    fun toggleHidden(context: String, crdKey: String) {
        ioScope.launch {
            dataStore.edit { prefs ->
                val pinned = decode(prefs[CRD_PINNED]).toMutableMap()
                val hidden = decode(prefs[CRD_HIDDEN]).toMutableMap()
                val pinnedHere = pinned[context]?.toMutableSet() ?: mutableSetOf()
                val hiddenHere = hidden[context]?.toMutableSet() ?: mutableSetOf()
                if (crdKey in hiddenHere) {
                    hiddenHere.remove(crdKey)
                } else {
                    hiddenHere.add(crdKey)
                    pinnedHere.remove(crdKey)
                }
                writeBack(prefs, pinned, context, pinnedHere, CRD_PINNED)
                writeBack(prefs, hidden, context, hiddenHere, CRD_HIDDEN)
            }
        }
    }

    private fun writeBack(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        map: MutableMap<String, Set<String>>,
        context: String,
        forContext: Set<String>,
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
    ) {
        if (forContext.isEmpty()) map.remove(context) else map[context] = forContext
        prefs[key] = json.encodeToString(map.mapValues { it.value.toList() })
    }

    private fun decode(raw: String?): Map<String, Set<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, List<String>>>(raw).mapValues { it.value.toSet() }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
