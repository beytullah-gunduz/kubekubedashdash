package com.kubekubedashdash.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON codec for the `Map<String, Boolean>` preference blobs (sidebar-section
 * and stats-panel expand overrides). Lives outside [PreferenceRepository] so
 * it can be unit-tested without touching the DataStore.
 */
internal object BoolMapCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Blank or malformed input decodes to an empty map — fail open to defaults. */
    fun decode(raw: String?): Map<String, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, Boolean>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encode(map: Map<String, Boolean>): String = json.encodeToString(map)
}
