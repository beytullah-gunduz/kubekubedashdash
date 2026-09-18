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

/** Cap on how many keys [computeRecordRecent] keeps per context. */
const val RECENT_CAP = 5

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

/**
 * Moves [key] to the front of its context's list, dedupes, and caps at [cap]
 * (dropping the oldest). A no-op — returns [map] unchanged — when [key] is
 * already first, or when it is one of [favourites]: a favourite is excluded
 * from the Recent section at render time, so storing the visit would only
 * burn one of the five slots on a row that is never shown.
 */
internal fun computeRecordRecent(
    map: Map<String, List<String>>,
    context: String,
    key: String,
    cap: Int = RECENT_CAP,
    favourites: Collection<String> = emptyList(),
): Map<String, List<String>> {
    if (key in favourites) return map
    val ctx = DemoContext.preferenceKey(context)
    val current = map[ctx].orEmpty()
    if (current.firstOrNull() == key) return map
    val next = (listOf(key) + current.filterNot { it == key }).take(cap)
    return updateContextList(map, ctx, next)
}

/** [map] without [key] in [context]'s list; the context entry goes when it empties. */
internal fun computeRemoveRecent(
    map: Map<String, List<String>>,
    context: String,
    key: String,
): Map<String, List<String>> {
    val ctx = DemoContext.preferenceKey(context)
    val current = map[ctx].orEmpty()
    if (key !in current) return map
    return updateContextList(map, ctx, current - key)
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
 * Per-cluster Favourites and Recent for the sidebar's built-in kinds and
 * CRDs, keyed by the same context string [CrdPreferenceRepository] uses —
 * folded through [DemoContext.preferenceKey], so every minted demo label
 * shares one row and a re-mint never loses them.
 * Stored as two JSON `Map<context, List<key>>` blobs in the shared DataStore.
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
    private val NAV_RECENTS by lazy { stringPreferencesKey("nav_recents_per_context") }

    private val _favouritesByContext = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val favouritesByContext: StateFlow<Map<String, List<String>>> = _favouritesByContext.asStateFlow()

    private val _recentsByContext = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val recentsByContext: StateFlow<Map<String, List<String>>> = _recentsByContext.asStateFlow()

    // Writes go through one consumer in arrival order. Launching each edit
    // as its own coroutine would let two quick navigations reach DataStore's
    // transaction lock in either order and persist A-then-B as B-then-A.
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        ioScope.launch {
            dataStore.data.recoveringPreferenceReads("NavPreferenceRepository").collect { p ->
                _favouritesByContext.value = decodeContextLists(p[NAV_FAVOURITES])
                _recentsByContext.value = decodeContextLists(p[NAV_RECENTS])
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
                val next = computeToggleFavourite(favourites, ctx, key)
                prefs[NAV_FAVOURITES] = encodeContextLists(next)
                // Becoming a favourite frees the Recent slot it was holding —
                // in the same transaction, so the two can never disagree.
                if (key in next[ctx].orEmpty()) {
                    val recents = decodeContextLists(prefs[NAV_RECENTS])
                    prefs[NAV_RECENTS] = encodeContextLists(computeRemoveRecent(recents, ctx, key))
                }
            }
        }
    }

    fun recordRecent(context: String, key: String) {
        if (context.isBlank()) return
        // Avoid the edit entirely when nothing would change — navigate()'s
        // own "target != current" guard doesn't stop e.g. Pods(statusFilter=…)
        // → Pods() from being two distinct navigations with the same key.
        val ctx = DemoContext.preferenceKey(context)
        if (_recentsByContext.value[ctx]?.firstOrNull() == key) return
        if (key in _favouritesByContext.value[ctx].orEmpty()) return
        writes.trySend {
            dataStore.edit { prefs ->
                val recents = decodeContextLists(prefs[NAV_RECENTS])
                val favourites = decodeContextLists(prefs[NAV_FAVOURITES])[ctx].orEmpty()
                prefs[NAV_RECENTS] = encodeContextLists(computeRecordRecent(recents, ctx, key, favourites = favourites))
            }
        }
    }
}
