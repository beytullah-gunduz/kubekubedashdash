package com.kubekubedashdash.data.repository

import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The on-disk form of the per-cluster metrics source map (`metrics_source_by_context`).
 *
 * Compatible with v1.31.0, which decodes the whole map with a three-value enum, so one unknown
 * value empties it and its next write persists the empty map. In-cluster is therefore stored as
 * `mode: "AUTO"` plus `inCluster.selected = true`, which v1.31.0 reads as plain Auto. (A v1.31.0
 * write still drops the `inCluster` object of the entry it rewrites.)
 *
 * Entries are decoded one at a time, and the store keeps every entry as raw JSON: one this
 * version cannot decode, written by a later release, stays in the map on every later write instead
 * of being dropped. Lives outside [PreferenceRepository] so it can be tested without a DataStore.
 */
internal object MetricsSourceCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** The entries as stored, decodable or not. Blank or malformed input is an empty map. */
    fun parseRaw(raw: String?): Map<String, JsonElement> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            (json.parseToJsonElement(raw) as? JsonObject)?.toMap() ?: emptyMap()
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }

    fun encodeRaw(entries: Map<String, JsonElement>): String = json.encodeToString(JsonObject.serializer(), JsonObject(entries))

    /** Only the entries this version understands. */
    fun decode(entries: Map<String, JsonElement>): Map<String, MetricsSourceSetting> {
        val decoded = LinkedHashMap<String, MetricsSourceSetting>()
        for ((key, element) in entries) decodeEntry(element)?.let { decoded[key] = it }
        return decoded
    }

    fun decode(raw: String?): Map<String, MetricsSourceSetting> = decode(parseRaw(raw))

    fun encodeEntry(setting: MetricsSourceSetting): JsonElement = json.encodeToJsonElement(OnDiskSetting.of(setting))

    /** [entries] with [key] set to [setting], or without it when [setting] is the default. The other entries are untouched. */
    fun withSetting(entries: Map<String, JsonElement>, key: String, setting: MetricsSourceSetting): Map<String, JsonElement> {
        val next = LinkedHashMap(entries)
        if (setting == MetricsSourceSetting()) next.remove(key) else next[key] = encodeEntry(setting)
        return next
    }

    private fun decodeEntry(element: JsonElement): MetricsSourceSetting? = try {
        json.decodeFromJsonElement<OnDiskSetting>(element).toSetting()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

// The v1.31.0 fields stay at the top level under their old names; mode is always written so a
// reader never has to guess it.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class OnDiskSetting(
    @EncodeDefault val mode: MetricsSourceMode = MetricsSourceMode.AUTO,
    val project: String = "",
    val location: String = "",
    val cluster: String = "",
    val inCluster: OnDiskInCluster? = null,
) {
    fun toSetting(): MetricsSourceSetting {
        val fields = inCluster
        return MetricsSourceSetting(
            mode = if (mode == MetricsSourceMode.AUTO && fields?.selected == true) MetricsSourceMode.IN_CLUSTER else mode,
            project = project,
            location = location,
            cluster = cluster,
            namespace = fields?.namespace.orEmpty(),
            service = fields?.service.orEmpty(),
            port = fields?.port.orEmpty(),
            pathPrefix = fields?.pathPrefix.orEmpty(),
            https = fields?.https ?: false,
        )
    }

    companion object {
        fun of(setting: MetricsSourceSetting): OnDiskSetting {
            val selected = setting.mode == MetricsSourceMode.IN_CLUSTER
            // Fields left from an earlier In-cluster choice are kept under Auto, GMP and Off too.
            val hasFields = selected ||
                setting.namespace.isNotEmpty() ||
                setting.service.isNotEmpty() ||
                setting.port.isNotEmpty() ||
                setting.pathPrefix.isNotEmpty() ||
                setting.https
            return OnDiskSetting(
                mode = if (selected) MetricsSourceMode.AUTO else setting.mode,
                project = setting.project,
                location = setting.location,
                cluster = setting.cluster,
                inCluster = if (hasFields) {
                    OnDiskInCluster(
                        selected = selected,
                        namespace = setting.namespace,
                        service = setting.service,
                        port = setting.port,
                        https = setting.https,
                        pathPrefix = setting.pathPrefix,
                    )
                } else {
                    null
                },
            )
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class OnDiskInCluster(
    @EncodeDefault val selected: Boolean = false,
    @EncodeDefault val namespace: String = "",
    @EncodeDefault val service: String = "",
    @EncodeDefault val port: String = "",
    @EncodeDefault val https: Boolean = false,
    @EncodeDefault val pathPrefix: String = "",
)
