package com.kubekubedashdash.helm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Helm v3.22.0 semantics (`pkg/chartutil/coalesce.go`, merge = false, no subcharts). */
class HelmValuesTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun coalesce(chart: String, user: String): JsonObject = HelmValues.coalesce(obj(chart), obj(user))

    private fun assertCoalesces(expected: String, chart: String, user: String) = assertEquals(obj(expected), coalesce(chart, user))

    // ── coalesce ────────────────────────────────────────────────────────────

    @Test
    fun `a user scalar overrides the chart default and unrelated defaults stay`() {
        assertCoalesces("""{"a":2,"b":"x"}""", chart = """{"a":1,"b":"x"}""", user = """{"a":2}""")
    }

    @Test
    fun `nested tables are merged key by key`() {
        assertCoalesces(
            """{"image":{"repository":"nginx","tag":"1.25","pullPolicy":"Always"}}""",
            chart = """{"image":{"repository":"nginx","tag":"latest","pullPolicy":"IfNotPresent"}}""",
            user = """{"image":{"tag":"1.25","pullPolicy":"Always"}}""",
        )
    }

    @Test
    fun `a top-level user null deletes a chart key`() {
        assertCoalesces("""{"b":2}""", chart = """{"a":1,"b":2}""", user = """{"a":null}""")
    }

    @Test
    fun `a nested user null deletes a non-null chart default`() {
        assertCoalesces(
            """{"db":{"port":5432}}""",
            chart = """{"db":{"host":"localhost","port":5432}}""",
            user = """{"db":{"host":null}}""",
        )
    }

    @Test
    fun `a top-level user null that the chart does not have stays null`() {
        assertCoalesces("""{"x":null,"a":1}""", chart = """{"a":1}""", user = """{"x":null}""")
    }

    @Test
    fun `a nested user null that the chart does not have is kept as null`() {
        assertCoalesces(
            """{"db":{"host":"localhost","extra":null}}""",
            chart = """{"db":{"host":"localhost"}}""",
            user = """{"db":{"extra":null}}""",
        )
    }

    @Test
    fun `a nested null on both sides is kept as null`() {
        assertCoalesces(
            """{"db":{"host":null}}""",
            chart = """{"db":{"host":null}}""",
            user = """{"db":{"host":null}}""",
        )
    }

    @Test
    fun `a chart default null is copied when the user has no such key, top level and nested`() {
        assertCoalesces(
            """{"top":null,"db":{"host":null,"port":1}}""",
            chart = """{"top":null,"db":{"host":null,"port":1}}""",
            user = """{}""",
        )
        assertCoalesces(
            """{"db":{"host":null,"port":2}}""",
            chart = """{"db":{"host":null,"port":1}}""",
            user = """{"db":{"port":2}}""",
        )
    }

    @Test
    fun `a user scalar over a chart table keeps the user scalar`() {
        assertCoalesces("""{"image":"nginx:1"}""", chart = """{"image":{"tag":"latest"}}""", user = """{"image":"nginx:1"}""")
        assertCoalesces("""{"a":{"b":"flat"}}""", chart = """{"a":{"b":{"c":1}}}""", user = """{"a":{"b":"flat"}}""")
    }

    @Test
    fun `a user table over a chart scalar keeps the user table`() {
        assertCoalesces("""{"image":{"tag":"1"}}""", chart = """{"image":"nginx"}""", user = """{"image":{"tag":"1"}}""")
        assertCoalesces("""{"a":{"b":{"c":1}}}""", chart = """{"a":{"b":"flat"}}""", user = """{"a":{"b":{"c":1}}}""")
    }

    @Test
    fun `arrays are replaced, not merged`() {
        assertCoalesces("""{"hosts":["c"]}""", chart = """{"hosts":["a","b"]}""", user = """{"hosts":["c"]}""")
        assertCoalesces("""{"x":{"hosts":["c"]}}""", chart = """{"x":{"hosts":["a","b"]}}""", user = """{"x":{"hosts":["c"]}}""")
    }

    @Test
    fun `user keys the chart does not know are kept`() {
        assertCoalesces("""{"a":1,"extra":{"k":"v"}}""", chart = """{"a":1}""", user = """{"extra":{"k":"v"}}""")
    }

    @Test
    fun `an empty chart or empty user values coalesce to the other side`() {
        assertCoalesces("""{"a":1}""", chart = """{}""", user = """{"a":1}""")
        assertCoalesces("""{"a":1}""", chart = """{"a":1}""", user = """{}""")
        assertCoalesces("""{}""", chart = """{}""", user = """{}""")
    }

    @Test
    fun `the inputs are not mutated`() {
        val chart = obj("""{"a":1,"image":{"tag":"latest","gone":"x"},"hosts":["a"]}""")
        val user = obj("""{"image":{"tag":"1.0","gone":null},"b":null}""")
        val chartBefore = chart.toString()
        val userBefore = user.toString()

        HelmValues.coalesce(chart, user)

        assertEquals(chartBefore, chart.toString())
        assertEquals(userBefore, user.toString())
    }

    // ── toYaml ──────────────────────────────────────────────────────────────

    @Test
    fun `keys are sorted at every depth`() {
        val yaml = HelmValues.toYaml(obj("""{"b":1,"a":{"z":1,"y":{"d":1,"c":2}},"c":[{"q":1,"p":2}]}"""))

        assertEquals(
            "a:\n  y:\n    c: 2\n    d: 1\n  z: 1\nb: 1\nc:\n- p: 2\n  q: 1\n",
            yaml,
        )
    }

    @Test
    fun `a multi-line string renders as a literal block`() {
        val yaml = HelmValues.toYaml(obj("""{"script":"line one\nline two\n"}"""))

        assertTrue(yaml.startsWith("script: |"), yaml)
        assertTrue(yaml.contains("  line one\n"), yaml)
        assertTrue(yaml.contains("  line two\n"), yaml)
        assertEquals("line one\nline two\n", load(yaml)["script"])
    }

    @Test
    fun `strings that look like booleans or numbers stay strings`() {
        val yaml = HelmValues.toYaml(obj("""{"flag":"true","port":"8080","version":"1.25.4","real":true,"count":3,"ratio":1.5,"big":12345678901234567890}"""))
        val parsed = load(yaml)

        assertEquals("true", parsed["flag"])
        assertEquals("8080", parsed["port"])
        assertEquals("1.25.4", parsed["version"])
        assertEquals(true, parsed["real"])
        assertEquals(3, parsed["count"])
        assertEquals(1.5, parsed["ratio"])
        assertEquals(java.math.BigInteger("12345678901234567890"), parsed["big"])
        // Quoted so that no reader turns them into a boolean or a number.
        assertTrue(yaml.contains("flag: 'true'") || yaml.contains("flag: \"true\""), yaml)
        assertTrue(yaml.contains("port: '8080'") || yaml.contains("port: \"8080\""), yaml)
    }

    @Test
    fun `null values, empty tables and empty arrays render`() {
        val yaml = HelmValues.toYaml(obj("""{"n":null,"t":{},"a":[]}"""))
        val parsed = load(yaml)

        assertTrue("n" in parsed && parsed["n"] == null)
        assertEquals(emptyMap<String, Any?>(), parsed["t"])
        assertEquals(emptyList<Any?>(), parsed["a"])
    }

    @Test
    fun `a long string is not folded across lines`() {
        val text = "word ".repeat(60).trim()
        val yaml = HelmValues.toYaml(obj("""{"text":"$text"}"""))

        assertEquals(1, yaml.trimEnd().lines().size, yaml)
        assertEquals(text, load(yaml)["text"])
    }

    @Test
    fun `an empty object is the empty string`() {
        assertEquals("", HelmValues.toYaml(JsonObject(emptyMap())))
    }

    @Suppress("UNCHECKED_CAST")
    private fun load(yaml: String): Map<String, Any?> = Load(LoadSettings.builder().build()).loadFromString(yaml) as Map<String, Any?>
}
