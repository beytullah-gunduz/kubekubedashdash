package com.kubekubedashdash.helm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.Random
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class HelmReleaseCodecTest {

    private companion object {
        const val MIB = 1024 * 1024
        const val MARKER = "marker-must-not-leak-4f7a"

        val FIXED_MESSAGES = setOf(
            "The Secret's release data is not valid base64.",
            "The release payload is not valid base64.",
            "The release payload is larger than 64 MiB once decompressed.",
            "The release payload is not valid gzip.",
            "The release payload is not a Helm release.",
        )
    }

    private val helm3Json = """
        {
          "name": "frontend",
          "info": {
            "first_deployed": "2026-03-01T10:15:30.123456789+01:00",
            "last_deployed": "2026-03-02T11:16:31.5+01:00",
            "deleted": "",
            "description": "Upgrade complete",
            "status": "deployed",
            "notes": "hello"
          },
          "chart": {
            "metadata": {"name": "frontend-web", "version": "1.3.1", "appVersion": "1.25.4", "apiVersion": "v2"},
            "lock": null,
            "templates": [],
            "values": {},
            "schema": null,
            "files": []
          },
          "config": {},
          "manifest": "",
          "hooks": [],
          "version": 3,
          "namespace": "default"
        }
    """.trimIndent()

    private val helm4Json = """
        {
          "name": "frontend",
          "info": {
            "last_deployed": "2026-03-02T10:16:31.5Z",
            "description": "Rollback to 2",
            "status": "deployed",
            "rollback_revision": 2
          },
          "chart": {
            "metadata": {"name": "frontend-web", "version": "1.3.0", "appVersion": "1.25.4"},
            "modtime": "2026-01-01T00:00:00Z",
            "schemamodtime": "2026-01-01T00:00:00Z",
            "files": [{"name": "README.md", "data": "", "modtime": "2026-01-01T00:00:00Z"}]
          },
          "apply_method": "csa",
          "version": 4,
          "namespace": "default"
        }
    """.trimIndent()

    // ── encode / decode ─────────────────────────────────────────────────────

    @Test
    fun `gzip round trip gives back the JSON`() {
        assertEquals(helm3Json, HelmReleaseCodec.decodeToJson(HelmReleaseCodec.encode(helm3Json)))
    }

    @Test
    fun `plain round trip gives back the JSON`() {
        val payload = HelmReleaseCodec.encode(helm3Json, gzip = false)
        assertEquals(helm3Json, HelmReleaseCodec.decodeToJson(payload))
        assertEquals(helm3Json, String(Base64.getDecoder().decode(payload), Charsets.UTF_8))
    }

    @Test
    fun `an encoded gzip payload starts with the gzip magic bytes Helm checks for`() {
        val bytes = Base64.getDecoder().decode(HelmReleaseCodec.encode(helm3Json))
        assertEquals(listOf<Byte>(0x1f, 0x8b.toByte(), 0x08), bytes.take(3))
    }

    @Test
    fun `a Secret's data round trips through the second base64 layer`() {
        val wire = Base64.getEncoder().encodeToString(HelmReleaseCodec.encode(helm3Json).toByteArray(Charsets.UTF_8))
        val payload = HelmReleaseCodec.secretDataToPayload(wire)
        assertEquals(helm3Json, HelmReleaseCodec.decodeToJson(payload))
    }

    @Test
    fun `a Secret's data that is not base64 is refused with a fixed message`() {
        val e = assertFailsWith<HelmDecodeException> { HelmReleaseCodec.secretDataToPayload("$MARKER!!") }
        assertEquals("The Secret's release data is not valid base64.", e.message)
    }

    // ── summaries ───────────────────────────────────────────────────────────

    @Test
    fun `parseSummary reads a Helm 3 release with offsets, nanoseconds and an empty deleted time`() {
        val summary = HelmReleaseCodec.parseSummary(helm3Json)
        assertEquals("frontend-web", summary.chartName)
        assertEquals("1.3.1", summary.chartVersion)
        assertEquals("frontend-web-1.3.1", summary.chart)
        assertEquals("1.25.4", summary.appVersion)
        assertEquals("deployed", summary.status)
        assertEquals("Upgrade complete", summary.description)
        assertEquals(Instant.parse("2026-03-01T09:15:30.123456789Z"), summary.firstDeployed)
        assertEquals(Instant.parse("2026-03-02T10:16:31.5Z"), summary.lastDeployed)
    }

    @Test
    fun `parseSummary reads a Helm 4 release, ignoring keys it does not know and a missing first_deployed`() {
        val summary = HelmReleaseCodec.parseSummary(helm4Json)
        assertEquals("frontend-web-1.3.0", summary.chart)
        assertEquals("Rollback to 2", summary.description)
        assertNull(summary.firstDeployed)
        assertEquals(Instant.parse("2026-03-02T10:16:31.5Z"), summary.lastDeployed)
    }

    @Test
    fun `a blank, zero or malformed time is null`() {
        assertNull(parseHelmTime(null))
        assertNull(parseHelmTime(""))
        assertNull(parseHelmTime("yesterday"))
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), parseHelmTime("2026-01-01T00:00:00Z"))
    }

    @Test
    fun `the chart label is the bare name when the version is blank and empty when there is no name`() {
        assertEquals("redis", HelmReleaseCodec.parseSummary("""{"chart":{"metadata":{"name":"redis"}}}""").chart)
        assertEquals("", HelmReleaseCodec.parseSummary("{}").chart)
    }

    @Test
    fun `parseDetail reads notes, values, manifest and resources`() {
        val json = buildJsonObject {
            putJsonObject("info") {
                put("status", "deployed")
                put("notes", "note text")
            }
            putJsonObject("chart") {
                putJsonObject("metadata") {
                    put("name", "demo")
                    put("version", "1.0.0")
                }
                putJsonObject("values") {
                    put("replicas", 1)
                    putJsonObject("image") { put("tag", "latest") }
                }
            }
            putJsonObject("config") {
                putJsonObject("image") { put("tag", "1.2.3") }
            }
            put("manifest", "---\n# Source: demo/templates/svc.yaml\napiVersion: v1\nkind: Service\nmetadata:\n  name: demo-svc\n")
        }.toString()

        val detail = HelmReleaseCodec.parseDetail(json, "team-a")

        assertEquals("demo-1.0.0", detail.summary.chart)
        assertEquals("note text", detail.notes)
        assertEquals("image:\n  tag: 1.2.3\n", detail.userValuesYaml)
        assertEquals("image:\n  tag: 1.2.3\nreplicas: 1\n", detail.computedValuesYaml)
        assertTrue(detail.manifest.contains("demo-svc"))
        assertEquals(detail.manifest, detail.maskedManifest)
        assertEquals(listOf(ManifestResource("v1", "Service", "demo-svc", "team-a")), detail.resources)
    }

    @Test
    fun `parseDetail of a release with no config or values has empty YAML`() {
        val detail = HelmReleaseCodec.parseDetail("""{"info":{"status":"deployed"},"manifest":null}""", "default")
        assertEquals("", detail.userValuesYaml)
        assertEquals("", detail.computedValuesYaml)
        assertEquals("", detail.manifest)
        assertTrue(detail.resources.isEmpty())
    }

    // ── malformed input ─────────────────────────────────────────────────────

    private fun assertFixedMessage(e: HelmDecodeException) {
        assertTrue(e.message in FIXED_MESSAGES, "message is one of the fixed strings: ${e.message}")
        assertFalse(e.message!!.contains(MARKER), "the message carries no input text")
    }

    @Test
    fun `a payload that is not base64 is refused`() {
        val e = assertFailsWith<HelmDecodeException> { HelmReleaseCodec.decodeToJson("$MARKER is !! not base64") }
        assertFixedMessage(e)
        assertEquals("The release payload is not valid base64.", e.message)
    }

    @Test
    fun `bytes that start like gzip but are not gzip are refused`() {
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 0x08) + "$MARKER-not-a-gzip-stream".toByteArray()
        val e = assertFailsWith<HelmDecodeException> {
            HelmReleaseCodec.decodeToJson(Base64.getEncoder().encodeToString(bytes))
        }
        assertFixedMessage(e)
        assertEquals("The release payload is not valid gzip.", e.message)
    }

    @Test
    fun `valid gzip of text that is not JSON is refused when parsed`() {
        val json = HelmReleaseCodec.decodeToJson(HelmReleaseCodec.encode("$MARKER is not json"))
        assertFixedMessage(assertFailsWith<HelmDecodeException> { HelmReleaseCodec.parseSummary(json) })
        assertFixedMessage(assertFailsWith<HelmDecodeException> { HelmReleaseCodec.parseDetail(json, "default") })
    }

    @Test
    fun `JSON null and a JSON array are refused when parsed`() {
        for (json in listOf("null", """["$MARKER"]""", "\"$MARKER\"", "42")) {
            assertFixedMessage(assertFailsWith<HelmDecodeException> { HelmReleaseCodec.parseSummary(json) })
            assertFixedMessage(assertFailsWith<HelmDecodeException> { HelmReleaseCodec.parseDetail(json, "default") })
        }
    }

    @Test
    fun `a config that is not an object is refused and the message holds no input text`() {
        val json = """{"config":"$MARKER","info":{"status":"deployed"}}"""
        assertFixedMessage(assertFailsWith<HelmDecodeException> { HelmReleaseCodec.parseDetail(json, "default") })
    }

    // ── size ────────────────────────────────────────────────────────────────

    @Test
    fun `a large release decodes, parses and shows every manifest line`() {
        val random = Random(42)
        val manifest = (1..2_000).joinToString("\n") { n ->
            "---\n# Source: big/templates/cm-$n.yaml\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm-$n\n" +
                "  namespace: default\ndata:\n  key-a: value-$n\n  key-b: value-$n"
        }
        val json = buildJsonObject {
            put("name", "big")
            putJsonObject("info") {
                put("status", "deployed")
                put("last_deployed", "2026-03-02T10:16:31Z")
            }
            putJsonObject("chart") {
                putJsonObject("metadata") {
                    put("name", "big")
                    put("version", "9.9.9")
                    put("appVersion", "1.0")
                }
                put("lock", JsonNull)
                putJsonArray("templates") {
                    repeat(2_000) { n ->
                        val data = ByteArray(4 * 1024).also(random::nextBytes)
                        add(
                            buildJsonObject {
                                put("name", "templates/t-$n.yaml")
                                put("data", Base64.getEncoder().encodeToString(data))
                            },
                        )
                    }
                }
                putJsonObject("values") { put("replicas", 1) }
                put("files", buildJsonArray {})
            }
            putJsonObject("config") {}
            put("manifest", manifest)
        }.toString()
        val payload = HelmReleaseCodec.encode(json)

        lateinit var decoded: String
        lateinit var summary: HelmReleaseSummary
        val summaryTime = measureTime {
            decoded = HelmReleaseCodec.decodeToJson(payload)
            summary = HelmReleaseCodec.parseSummary(decoded)
        }
        lateinit var detail: HelmReleaseDetail
        val detailTime = measureTime { detail = HelmReleaseCodec.parseDetail(decoded, "default") }

        println(
            "HelmReleaseCodecTest large payload: ${json.length / MIB} MiB JSON, ${payload.length / MIB} MiB payload; " +
                "decodeToJson + parseSummary ${summaryTime.inWholeMilliseconds} ms, parseDetail ${detailTime.inWholeMilliseconds} ms",
        )
        assertEquals("big-9.9.9", summary.chart)
        assertEquals(20_000, detail.manifest.split("\n").size)
        assertEquals(2_000, detail.resources.size)
        assertTrue(summaryTime.inWholeSeconds < 60, "decode took $summaryTime")
        assertTrue(detailTime.inWholeSeconds < 60, "parseDetail took $detailTime")
    }

    @Test
    fun `a payload that decompresses past 64 MiB is refused`() {
        // Stream the zeros through the compressor in chunks: a 70 MiB array would eat the test heap.
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { out ->
            val chunk = ByteArray(64 * 1024)
            repeat(70 * 1024 / 64) { out.write(chunk) }
        }
        val payload = Base64.getEncoder().encodeToString(compressed.toByteArray())

        val e = assertFailsWith<HelmDecodeException> { HelmReleaseCodec.decodeToJson(payload) }

        assertEquals("The release payload is larger than 64 MiB once decompressed.", e.message)
    }
}
