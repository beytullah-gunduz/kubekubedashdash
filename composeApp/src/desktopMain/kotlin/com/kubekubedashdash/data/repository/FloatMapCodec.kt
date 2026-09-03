package com.kubekubedashdash.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON codec for the `Map<String, Float>` preference blob (detail-pane width
 * per resource kind). Lives outside [PreferenceRepository] so it can be
 * unit-tested without touching the DataStore.
 */
internal object FloatMapCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Blank or malformed input decodes to an empty map — fail open to defaults. */
    fun decode(raw: String?): Map<String, Float> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, Float>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encode(map: Map<String, Float>): String = json.encodeToString(map)
}
