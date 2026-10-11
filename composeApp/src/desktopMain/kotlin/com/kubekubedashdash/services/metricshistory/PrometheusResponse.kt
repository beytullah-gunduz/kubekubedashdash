package com.kubekubedashdash.services.metricshistory

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the Prometheus HTTP API's JSON envelope; API JSON is parsed as a tree, with no generated serializers. */
internal object PrometheusResponse {
    sealed interface Parsed {
        data class Matrix(val series: List<Series>) : Parsed

        /** The first sample of a vector; null when the vector is empty. */
        data class Scalar(val value: Double?) : Parsed

        data class Error(val errorType: String) : Parsed

        data object Malformed : Parsed
    }

    private sealed interface Opened {
        data class Result(val items: JsonArray) : Opened

        data class Stop(val parsed: Parsed) : Opened
    }

    /** [nameLabel] = the metric label naming each series (null → ""). Non-finite samples are dropped; series sort by name. */
    fun parseMatrix(body: String, nameLabel: String?): Parsed = parse {
        when (val opened = open(body, "matrix")) {
            is Opened.Stop -> opened.parsed

            is Opened.Result -> Parsed.Matrix(
                opened.items.mapNotNull { item ->
                    val entry = item.jsonObject
                    val name = nameLabel?.let { entry["metric"]?.jsonObject?.get(it)?.jsonPrimitive?.contentOrNull }.orEmpty()
                    val points = entry["values"]?.jsonArray.orEmpty().mapNotNull { samplePoint(it.jsonArray) }
                    if (points.isEmpty()) null else Series(name, points)
                }.sortedBy { it.name },
            )
        }
    }

    fun parseVectorFirstValue(body: String): Parsed = parse {
        when (val opened = open(body, "vector")) {
            is Opened.Stop -> opened.parsed

            is Opened.Result -> {
                val first = opened.items.firstOrNull() ?: return@parse Parsed.Scalar(null)
                val sample = first.jsonObject["value"]?.jsonArray ?: return@parse Parsed.Malformed
                require(sample.size >= 2) { "a sample is [timestamp, value]" }
                Parsed.Scalar(sample[1].jsonPrimitive.content.toDoubleOrNull())
            }
        }
    }

    /** The envelope's errorType, or null when the body is not a Prometheus error envelope. */
    fun errorTypeOf(body: String): String? = try {
        val root = Json.parseToJsonElement(body).jsonObject
        if (root["status"]?.jsonPrimitive?.contentOrNull == "error") {
            root["errorType"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        } else {
            null
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    // jsonObject / jsonPrimitive / jsonArray throw IllegalArgumentException on the wrong shape.
    private inline fun parse(block: () -> Parsed): Parsed = try {
        block()
    } catch (_: SerializationException) {
        Parsed.Malformed
    } catch (_: IllegalArgumentException) {
        Parsed.Malformed
    }

    private fun open(body: String, resultType: String): Opened {
        val root = Json.parseToJsonElement(body).jsonObject
        when (root["status"]?.jsonPrimitive?.contentOrNull) {
            "success" -> Unit
            "error" -> return Opened.Stop(Parsed.Error(root["errorType"]?.jsonPrimitive?.contentOrNull ?: "unknown"))
            else -> return Opened.Stop(Parsed.Malformed)
        }
        val data = root["data"]?.jsonObject ?: return Opened.Stop(Parsed.Malformed)
        if (data["resultType"]?.jsonPrimitive?.contentOrNull != resultType) return Opened.Stop(Parsed.Malformed)
        val result = data["result"]?.jsonArray ?: return Opened.Stop(Parsed.Malformed)
        return Opened.Result(result)
    }

    // A sample is [unix seconds as a float, "value" as a string]; "NaN" and "+Inf" are legal values and are skipped.
    private fun samplePoint(sample: JsonArray): SeriesPoint? {
        require(sample.size >= 2) { "a sample is [timestamp, value]" }
        val epochSec = sample[0].jsonPrimitive.double.toLong()
        val value = sample[1].jsonPrimitive.content.toDoubleOrNull()
        return if (value == null || !value.isFinite()) null else SeriesPoint(epochSec, value)
    }
}
