package com.kubekubedashdash.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON codec for the `List<String>` preference blob (palette recents). Lives
 * outside [PreferenceRepository] so it can be unit-tested without touching
 * the DataStore.
 */
internal object StringListCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Blank or malformed input decodes to an empty list — fail open to defaults. */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(list: List<String>): String = json.encodeToString(list)
}
