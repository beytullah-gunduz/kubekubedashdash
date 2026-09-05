package com.kubekubedashdash.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON codec for the `Map<String, String>` preference blobs (cluster color
 * overrides, default namespace per cluster). Lives outside
 * [PreferenceRepository] so it can be unit-tested without touching the
 * DataStore.
 */
internal object StringMapCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Blank or malformed input decodes to an empty map — fail open to defaults. */
    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encode(map: Map<String, String>): String = json.encodeToString(map)
}
