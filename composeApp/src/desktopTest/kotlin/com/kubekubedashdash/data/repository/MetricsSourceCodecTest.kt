package com.kubekubedashdash.data.repository

import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A copy of v1.31.0's on-disk DTO: a three-value enum and no in-cluster fields.
@Serializable
private enum class V1310Mode { AUTO, GMP, OFF }

@Serializable
private data class V1310Setting(
    val mode: V1310Mode = V1310Mode.AUTO,
    val project: String = "",
    val location: String = "",
    val cluster: String = "",
)

/**
 * The on-disk form of the metrics source map: every mode round-trips, v1.31.0's own strings still
 * decode, v1.31.0 reads an In-cluster entry as Auto, and an entry this version cannot decode is
 * kept verbatim. Pure: strings in, strings out; no DataStore.
 */
class MetricsSourceCodecTest {

    private val v1310Json = Json { ignoreUnknownKeys = true }

    private val gmp = MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster")
    private val off = MetricsSourceSetting(MetricsSourceMode.OFF)
    private val inCluster = MetricsSourceSetting(
        mode = MetricsSourceMode.IN_CLUSTER,
        namespace = "monitoring",
        service = "prometheus-server",
        port = "80",
        pathPrefix = "/prom",
        https = true,
    )

    private fun roundTrip(setting: MetricsSourceSetting): MetricsSourceSetting? {
        val raw = MetricsSourceCodec.encodeRaw(MetricsSourceCodec.withSetting(emptyMap(), "example-context", setting))
        return MetricsSourceCodec.decode(raw)["example-context"]
    }

    @Test
    fun `every mode round-trips, with its fields`() {
        assertEquals(gmp, roundTrip(gmp))
        assertEquals(off, roundTrip(off))
        assertEquals(inCluster, roundTrip(inCluster))
        assertEquals(inCluster.copy(https = false, pathPrefix = ""), roundTrip(inCluster.copy(https = false, pathPrefix = "")))
    }

    @Test
    fun `fields left from another mode round-trip under every mode`() {
        val leftovers = inCluster.copy(mode = MetricsSourceMode.AUTO)
        assertEquals(leftovers, roundTrip(leftovers))
        assertEquals(inCluster.copy(mode = MetricsSourceMode.OFF), roundTrip(inCluster.copy(mode = MetricsSourceMode.OFF)))
        val gmpWithLeftovers = gmp.copy(namespace = "monitoring", https = true)
        assertEquals(gmpWithLeftovers, roundTrip(gmpWithLeftovers))
        val autoWithGmpFields = gmp.copy(mode = MetricsSourceMode.AUTO)
        assertEquals(autoWithGmpFields, roundTrip(autoWithGmpFields))
        val onlyHttps = MetricsSourceSetting(https = true)
        assertEquals(onlyHttps, roundTrip(onlyHttps))
    }

    @Test
    fun `the default setting is not stored`() {
        assertEquals(emptyMap(), MetricsSourceCodec.withSetting(emptyMap(), "example-context", MetricsSourceSetting()))
        val withOther = MetricsSourceCodec.withSetting(MetricsSourceCodec.withSetting(emptyMap(), "a", off), "b", gmp)
        assertEquals(listOf("b"), MetricsSourceCodec.withSetting(withOther, "a", MetricsSourceSetting()).keys.toList())
    }

    @Test
    fun `a Managed Prometheus or Off entry is written exactly as v1_31_0 wrote it`() {
        assertEquals(
            """{"example-context":{"mode":"GMP","project":"example-project","location":"us-central1","cluster":"example-cluster"}}""",
            MetricsSourceCodec.encodeRaw(MetricsSourceCodec.withSetting(emptyMap(), "example-context", gmp)),
        )
        assertEquals(
            """{"example-context":{"mode":"OFF"}}""",
            MetricsSourceCodec.encodeRaw(MetricsSourceCodec.withSetting(emptyMap(), "example-context", off)),
        )
    }

    @Test
    fun `In-cluster is stored as Auto plus a selected inCluster object`() {
        val entry = MetricsSourceCodec.encodeEntry(inCluster).jsonObject

        assertEquals("AUTO", entry.getValue("mode").jsonPrimitive.content)
        val fields = entry.getValue("inCluster").jsonObject
        assertTrue(fields.getValue("selected").jsonPrimitive.boolean)
        assertEquals("monitoring", fields.getValue("namespace").jsonPrimitive.content)
        assertEquals("prometheus-server", fields.getValue("service").jsonPrimitive.content)
        assertEquals("80", fields.getValue("port").jsonPrimitive.content)
        assertTrue(fields.getValue("https").jsonPrimitive.boolean)
        assertEquals("/prom", fields.getValue("pathPrefix").jsonPrimitive.content)
    }

    @Test
    fun `fields kept under Auto are stored with selected false, and a plain Auto entry has no inCluster object`() {
        val kept = MetricsSourceCodec.encodeEntry(inCluster.copy(mode = MetricsSourceMode.AUTO)).jsonObject
        assertEquals("AUTO", kept.getValue("mode").jsonPrimitive.content)
        assertFalse(kept.getValue("inCluster").jsonObject.getValue("selected").jsonPrimitive.boolean)

        assertFalse("inCluster" in MetricsSourceCodec.encodeEntry(gmp).jsonObject)
        assertFalse("inCluster" in MetricsSourceCodec.encodeEntry(off).jsonObject)
    }

    @Test
    fun `strings v1_31_0 wrote decode unchanged`() {
        val raw = """
            {
              "gke_example-project_us-central1_example-cluster": {"mode":"GMP","project":"example-project","location":"us-central1","cluster":"example-cluster"},
              "example-context": {"mode":"OFF"},
              "example-context-2": {"project":"example-project","location":"us-central1","cluster":"example-cluster"}
            }
        """.trimIndent()

        assertEquals(
            mapOf(
                "gke_example-project_us-central1_example-cluster" to gmp,
                "example-context" to off,
                "example-context-2" to gmp.copy(mode = MetricsSourceMode.AUTO),
            ),
            MetricsSourceCodec.decode(raw),
        )
    }

    @Test
    fun `v1_31_0 reads an In-cluster entry as plain Auto`() {
        val raw = MetricsSourceCodec.encodeRaw(MetricsSourceCodec.withSetting(emptyMap(), "example-context", inCluster))

        val seenByOldRelease = v1310Json.decodeFromString<Map<String, V1310Setting>>(raw)

        assertEquals(mapOf("example-context" to V1310Setting()), seenByOldRelease)
        assertEquals(V1310Mode.AUTO, seenByOldRelease.getValue("example-context").mode)
    }

    @Test
    fun `a hand-written IN_CLUSTER mode reads as In-cluster`() {
        val decoded = MetricsSourceCodec.decode("""{"example-context":{"mode":"IN_CLUSTER","inCluster":{"namespace":"monitoring","service":"prometheus-server","port":"80"}}}""")

        assertEquals(
            MetricsSourceSetting(MetricsSourceMode.IN_CLUSTER, namespace = "monitoring", service = "prometheus-server", port = "80"),
            decoded["example-context"],
        )
    }

    @Test
    fun `an inCluster object that is only partly there fills the rest with defaults`() {
        val decoded = MetricsSourceCodec.decode("""{"example-context":{"mode":"AUTO","inCluster":{"selected":true,"service":"prometheus-server"}}}""")

        assertEquals(MetricsSourceSetting(MetricsSourceMode.IN_CLUSTER, service = "prometheus-server"), decoded["example-context"])
    }

    @Test
    fun `unknown keys in an entry are ignored`() {
        val decoded = MetricsSourceCodec.decode("""{"example-context":{"mode":"OFF","retention":"7d","inCluster":{"selected":false,"tenant":"team-a"}}}""")

        assertEquals(off, decoded["example-context"])
    }

    @Test
    fun `an entry that cannot be decoded is left out of the decoded map and the others still decode`() {
        val raw = """
            {
              "future": {"mode":"QUANTUM","project":"example-project"},
              "wrong-type": "OFF",
              "wrong-field": {"mode":"GMP","project":42},
              "example-context": {"mode":"OFF"}
            }
        """.trimIndent()

        assertEquals(mapOf("example-context" to off), MetricsSourceCodec.decode(raw))
    }

    @Test
    fun `an entry that cannot be decoded survives a write for another key, byte for byte`() {
        val future = Json.parseToJsonElement("""{"mode":"QUANTUM","project":"example-project","extra":{"n":7}}""")
        val odd = Json.parseToJsonElement("\"OFF\"")
        val unknownKeys = Json.parseToJsonElement("""{"mode":"OFF","retention":"7d"}""")
        val stored = mapOf("future" to future, "odd" to odd, "unknown-keys" to unknownKeys)

        val written = MetricsSourceCodec.withSetting(stored, "example-context", inCluster)
        val reread = MetricsSourceCodec.parseRaw(MetricsSourceCodec.encodeRaw(written))

        assertEquals(future, reread["future"])
        assertEquals(odd, reread["odd"])
        assertEquals(unknownKeys, reread["unknown-keys"], "a decodable entry's unknown keys are not rewritten either")
        assertEquals(inCluster, MetricsSourceCodec.decode(reread)["example-context"])
        assertNull(MetricsSourceCodec.decode(reread)["future"])
    }

    @Test
    fun `resetting a key to the default leaves an entry that cannot be decoded in the map`() {
        val future = Json.parseToJsonElement("""{"mode":"QUANTUM"}""")
        val stored = mapOf("future" to future, "example-context" to MetricsSourceCodec.encodeEntry(off))

        val written = MetricsSourceCodec.withSetting(stored, "example-context", MetricsSourceSetting())

        assertEquals(mapOf("future" to future), written)
    }

    @Test
    fun `writing the key of an entry that cannot be decoded replaces it`() {
        val stored = mapOf("example-context" to Json.parseToJsonElement("""{"mode":"QUANTUM"}"""))

        val written = MetricsSourceCodec.withSetting(stored, "example-context", off)

        assertEquals(off, MetricsSourceCodec.decode(written)["example-context"])
    }

    @Test
    fun `blank or malformed input is an empty map`() {
        for (raw in listOf(null, "", "  ", "{", "not json", "[]", "\"x\"", "42", "null")) {
            assertEquals(emptyMap(), MetricsSourceCodec.parseRaw(raw), "'$raw'")
            assertEquals(emptyMap(), MetricsSourceCodec.decode(raw), "'$raw'")
        }
    }

    @Test
    fun `the raw map keeps its entries in order`() {
        val raw = """{"b":{"mode":"OFF"},"a":{"mode":"OFF"},"c":{"mode":"OFF"}}"""

        assertEquals(listOf("b", "a", "c"), MetricsSourceCodec.parseRaw(raw).keys.toList())
        assertEquals(raw, MetricsSourceCodec.encodeRaw(MetricsSourceCodec.parseRaw(raw)))
        assertEquals("{}", MetricsSourceCodec.encodeRaw(emptyMap()))
    }
}
