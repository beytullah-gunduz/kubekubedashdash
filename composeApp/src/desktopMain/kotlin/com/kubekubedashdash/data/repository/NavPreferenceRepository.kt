package com.kubekubedashdash.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubekubedashdash.data.datastore.dataStorePreferencesInstance
import com.kubekubedashdash.util.DemoContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure favourite-toggle. Favourites are an ordered list — not a set — so the
 * rail shows them in the order they were added rather than resting on
 * `LinkedHashSet` iteration behaviour. Toggling an absent [key] appends it;
 * toggling a present one removes it.
 */
internal fun computeToggleFavourite(
    map: Map<String, List<String>>,
    context: String,
    key: String,
): Map<String, List<String>> {
    val ctx = DemoContext.preferenceKey(context)
    val current = map[ctx].orEmpty()
    val next = if (key in current) current - key else current + key
    return updateContextList(map, ctx, next)
}

private fun updateContextList(
    map: Map<String, List<String>>,
    context: String,
    next: List<String>,
): Map<String, List<String>> {
    val mutable = map.toMutableMap()
    if (next.isEmpty()) mutable.remove(context) else mutable[context] = next
    return mutable
}

private val navPreferenceJson = Json { ignoreUnknownKeys = true }

/** Blank or malformed input decodes to an empty map — fail open to defaults. */
internal fun decodeContextLists(raw: String?): Map<String, List<String>> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        navPreferenceJson.decodeFromString<Map<String, List<String>>>(raw)
    } catch (_: Exception) {
        emptyMap()
    }
}

internal fun encodeContextLists(map: Map<String, List<String>>): String = navPreferenceJson.encodeToString(map)

/**
 * Per-cluster Favourites for the sidebar's built-in kinds and CRDs, keyed by
 * the same context string [CrdPreferenceRepository] uses — folded through
 * [DemoContext.preferenceKey], so every minted demo label shares one row and a
 * re-mint never loses them.
 * Stored as a JSON `Map<context, List<key>>` blob in the shared DataStore. A
 * sidebar Recent section once stored a second blob under
 * `nav_recents_per_context`; nothing reads it any more and it is left in place
 * (an unread key is harmless; a purge would add a start-up read-and-write path).
 * A built-in key is `screenKeyOf`'s value for the kind; a CRD key is
 * `"${group}/${kind}"` (`CrdInfo.key`) — the two namespaces never collide
 * because a simple class name never contains `/`.
 */
object NavPreferenceRepository {

    private val dataStore: DataStore<Preferences> by lazy { dataStorePreferencesInstance }

    // One writer at a time, in call order. Every setter updates memory and
    // launches its persist without awaiting it; on a shared pool two quick
    // launches can reach DataStore in either order, so a rapid A-then-B could
    // persist B-then-A and come back as A on the next start (and the
    // preference tests raced each other the same way on CI).
    private val ioScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob() + preferenceWriteFailureHandler("NavPreferenceRepository"))

    private val NAV_FAVOURITES by lazy { stringPreferencesKey("nav_favourites_per_context") }

    private val _favouritesByContext = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val favouritesByContext: StateFlow<Map<String, List<String>>> = _favouritesByContext.asStateFlow()

    // Writes go through one consumer in arrival order. Launching each edit
    // as its own coroutine would let two quick toggles reach DataStore's
    // transaction lock in either order and persist A-then-B as B-then-A.
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        ioScope.launch {
            dataStore.data.recoveringPreferenceReads("NavPreferenceRepository").collect { p ->
                _favouritesByContext.value = decodeContextLists(p[NAV_FAVOURITES])
            }
        }
        // One failed edit must not end the consumer: every later toggle would
        // be queued for nobody and dropped without a trace.
        ioScope.launch { for (write in writes) runPreferenceWrite("NavPreferenceRepository") { write() } }
    }

    fun toggleFavourite(context: String, key: String) {
        if (context.isBlank()) return
        val ctx = DemoContext.preferenceKey(context)
        writes.trySend {
            dataStore.edit { prefs ->
                val favourites = decodeContextLists(prefs[NAV_FAVOURITES])
                prefs[NAV_FAVOURITES] = encodeContextLists(computeToggleFavourite(favourites, ctx, key))
            }
        }
    }
}
