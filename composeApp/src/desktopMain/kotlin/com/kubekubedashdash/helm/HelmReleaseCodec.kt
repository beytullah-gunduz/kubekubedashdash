package com.kubekubedashdash.helm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Reads and writes Helm's stored release payload: JSON, gzip at best compression, base64
 * (`pkg/storage/driver/util.go`). A Secret keeps that base64 text as bytes, so on the wire
 * it is base64-encoded a second time; a ConfigMap keeps it as is.
 *
 * Every failure is a [HelmDecodeException] with one of the fixed messages below. The
 * original exception is dropped on purpose: a kotlinx.serialization message echoes the JSON
 * input, and a release payload can hold values and notes.
 */
object HelmReleaseCodec {
    /** The Secret / ConfigMap data key holding the payload. */
    const val DATA_KEY = "release"

    /** A payload that decompresses to more than this is refused (gzip-bomb guard). */
    const val MAX_DECOMPRESSED_BYTES: Int = 64 * 1024 * 1024

    private const val CHUNK_BYTES = 64 * 1024

    private const val NOT_A_RELEASE = "The release payload is not a Helm release."
    private const val TOO_LARGE = "The release payload is larger than 64 MiB once decompressed."

    // Helm's gzip check: a payload is compressed only when it starts with 1f 8b 08.
    private const val GZIP_MAGIC_0: Byte = 0x1f
    private const val GZIP_MAGIC_1: Byte = 0x8b.toByte()
    private const val GZIP_MAGIC_2: Byte = 0x08

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Mirrors Helm's `encodeRelease`. [gzip] false writes the pre-compression form (base64 of
     * the plain JSON), which Helm still reads. Used by the demo seed and the tests.
     */
    fun encode(json: String, gzip: Boolean = true): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (!gzip) return Base64.getEncoder().encodeToString(bytes)
        val buffer = ByteArrayOutputStream()
        object : GZIPOutputStream(buffer) {
            init {
                def.setLevel(Deflater.BEST_COMPRESSION)
            }
        }.use { it.write(bytes) }
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }

    /** A Secret's `data.release` as fabric8 returns it (wire base64) -> Helm's payload text. */
    fun secretDataToPayload(wireValue: String): String = try {
        String(Base64.getDecoder().decode(wireValue.trim()), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        throw HelmDecodeException("The Secret's release data is not valid base64.")
    }

    /** Helm's payload text -> the release JSON. */
    fun decodeToJson(payload: String): String {
        val bytes = try {
            Base64.getDecoder().decode(payload.trim())
        } catch (_: IllegalArgumentException) {
            throw HelmDecodeException("The release payload is not valid base64.")
        }
        if (bytes.size > 3 && bytes[0] == GZIP_MAGIC_0 && bytes[1] == GZIP_MAGIC_1 && bytes[2] == GZIP_MAGIC_2) {
            return gunzipToString(bytes)
        }
        if (bytes.size > MAX_DECOMPRESSED_BYTES) throw HelmDecodeException(TOO_LARGE)
        return String(bytes, Charsets.UTF_8)
    }

    private fun gunzipToString(bytes: ByteArray): String {
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(CHUNK_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DECOMPRESSED_BYTES) throw HelmDecodeException(TOO_LARGE)
                    out.write(chunk, 0, read)
                }
                return out.toString(Charsets.UTF_8)
            }
        } catch (_: IOException) {
            throw HelmDecodeException("The release payload is not valid gzip.")
        }
    }

    fun parseSummary(json: String): HelmReleaseSummary {
        val dto = decode<SummaryDto>(json)
        return summaryOf(dto.info, dto.chart?.metadata)
    }

    fun parseDetail(json: String, releaseNamespace: String): HelmReleaseDetail {
        val dto = decode<DetailDto>(json)
        val config = dto.config ?: JsonObject(emptyMap())
        val chartValues = dto.chart?.values ?: JsonObject(emptyMap())
        val manifest = dto.manifest ?: ""
        return HelmReleaseDetail(
            summary = summaryOf(dto.info, dto.chart?.metadata),
            notes = dto.info?.notes ?: "",
            userValuesYaml = if (config.isEmpty()) "" else HelmValues.toYaml(config),
            computedValuesYaml = HelmValues.toYaml(HelmValues.coalesce(chartValues, config)),
            manifest = manifest,
            maskedManifest = HelmManifest.mask(manifest),
            resources = HelmManifest.resources(manifest, releaseNamespace),
        )
    }

    private fun summaryOf(info: InfoDto?, metadata: MetadataDto?) = HelmReleaseSummary(
        chartName = metadata?.name ?: "",
        chartVersion = metadata?.version ?: "",
        appVersion = metadata?.appVersion ?: "",
        description = info?.description ?: "",
        status = info?.status ?: "",
        firstDeployed = parseHelmTime(info?.firstDeployed),
        lastDeployed = parseHelmTime(info?.lastDeployed),
    )

    private inline fun <reified T> decode(json: String): T = try {
        jsonFormat.decodeFromString<T>(json)
    } catch (_: SerializationException) {
        throw HelmDecodeException(NOT_A_RELEASE)
    } catch (_: IllegalArgumentException) {
        throw HelmDecodeException(NOT_A_RELEASE)
    }

    @Serializable
    private class SummaryDto(
        val info: InfoDto? = null,
        val chart: ChartSummaryDto? = null,
    )

    @Serializable
    private class InfoDto(
        @SerialName("first_deployed") val firstDeployed: String? = null,
        @SerialName("last_deployed") val lastDeployed: String? = null,
        val description: String? = null,
        val status: String? = null,
        val notes: String? = null,
    )

    @Serializable
    private class ChartSummaryDto(val metadata: MetadataDto? = null)

    @Serializable
    private class MetadataDto(
        val name: String? = null,
        val version: String? = null,
        val appVersion: String? = null,
    )

    @Serializable
    private class DetailDto(
        val info: InfoDto? = null,
        val chart: ChartDetailDto? = null,
        val config: JsonObject? = null,
        val manifest: String? = null,
    )

    @Serializable
    private class ChartDetailDto(
        val metadata: MetadataDto? = null,
        val values: JsonObject? = null,
    )
}

/** Blank, absent or unparsable -> null (Helm 3 writes `""` for a zero time, Helm 4 omits it). */
internal fun parseHelmTime(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}
