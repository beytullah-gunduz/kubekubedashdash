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
                val pinned = decode(prefs[CRD_PINNED])
                val hidden = decode(prefs[CRD_HIDDEN])
                val (newPinned, newHidden) = computeTogglePinned(pinned, hidden, context, crdKey)
                prefs[CRD_PINNED] = encode(newPinned)
                prefs[CRD_HIDDEN] = encode(newHidden)
            }
        }
    }

    fun toggleHidden(context: String, crdKey: String) {
        ioScope.launch {
            dataStore.edit { prefs ->
                val pinned = decode(prefs[CRD_PINNED])
                val hidden = decode(prefs[CRD_HIDDEN])
                val (newPinned, newHidden) = computeToggleHidden(pinned, hidden, context, crdKey)
                prefs[CRD_PINNED] = encode(newPinned)
                prefs[CRD_HIDDEN] = encode(newHidden)
            }
        }
    }

    /**
     * Pure pin-toggle. If the key is already pinned in this context, unpin it
     * (no-op on the hidden map). Otherwise pin it and ensure it's not hidden
     * — pinning auto-unhides because pin and hide are mutually exclusive.
     */
    internal fun computeTogglePinned(
        pinned: Map<String, Set<String>>,
        hidden: Map<String, Set<String>>,
        context: String,
        crdKey: String,
    ): Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
        val pinnedHere = pinned[context].orEmpty()
        val hiddenHere = hidden[context].orEmpty()
        return if (crdKey in pinnedHere) {
            updateContext(pinned, context, pinnedHere - crdKey) to hidden
        } else {
            updateContext(pinned, context, pinnedHere + crdKey) to
                updateContext(hidden, context, hiddenHere - crdKey)
        }
    }

    /**
     * Pure hide-toggle. Symmetric to [computeTogglePinned] — hiding auto-unpins.
     */
    internal fun computeToggleHidden(
        pinned: Map<String, Set<String>>,
        hidden: Map<String, Set<String>>,
        context: String,
        crdKey: String,
    ): Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
        val pinnedHere = pinned[context].orEmpty()
        val hiddenHere = hidden[context].orEmpty()
        return if (crdKey in hiddenHere) {
            pinned to updateContext(hidden, context, hiddenHere - crdKey)
        } else {
            updateContext(pinned, context, pinnedHere - crdKey) to
                updateContext(hidden, context, hiddenHere + crdKey)
        }
    }

    private fun updateContext(
        map: Map<String, Set<String>>,
        context: String,
        next: Set<String>,
    ): Map<String, Set<String>> {
        val mutable = map.toMutableMap()
        if (next.isEmpty()) mutable.remove(context) else mutable[context] = next
        return mutable
    }

    private fun encode(map: Map<String, Set<String>>): String = json.encodeToString(map.mapValues { it.value.toList() })

    internal fun decode(raw: String?): Map<String, Set<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, List<String>>>(raw).mapValues { it.value.toSet() }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
