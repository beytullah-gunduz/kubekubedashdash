package com.kubekubedashdash.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubekubedashdash.data.datastore.dataStorePreferencesInstance
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The metrics source setting is stored per cluster under [DemoContext.preferenceKey]; the default
 * (Auto, no fields) is never stored. Runs only against the Gradle test-data store, and puts back
 * every key it touched. The raw-string tests seed the store through the DataStore itself, as an
 * older or a later release would have left it.
 */
class MetricsSourcePreferenceTest {

    private val bare = DemoContext.MOCK_CONTEXT_NAME
    private val minted = "demo-cluster (mock) #3"
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
    private val storeKey = stringPreferencesKey("metrics_source_by_context")

    @BeforeTest
    fun refuseRealDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @AfterTest
    fun restoreDefaults() = runBlocking<Unit> {
        // The teardown runs even when the guard above failed; it must not touch a real store then.
        if (!SystemDirectories.dataDirectory.contains("test-data")) return@runBlocking
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        // A test may have seeded entries that no setter owns.
        dataStorePreferencesInstance.edit { it.remove(storeKey) }
        for (context in listOf("example-context", "example-context-2", bare, minted)) {
            PreferenceRepository.setMetricsSource(context, MetricsSourceSetting())
        }
    }

    private suspend fun loaded() {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
    }

    private suspend fun seedRaw(raw: String) {
        dataStorePreferencesInstance.edit { it[storeKey] = raw }
    }

    // The map as the file holds it, one raw JSON value per key.
    private suspend fun storedEntries() = Json.parseToJsonElement(checkNotNull(dataStorePreferencesInstance.data.first()[storeKey]) { "nothing stored" }).jsonObject

    @Test
    fun `a Managed Prometheus setting round-trips through the store`() = runBlocking<Unit> {
        loaded()

        PreferenceRepository.setMetricsSource("example-context", gmp)

        assertEquals(gmp, PreferenceRepository.metricsSourceByContext.value["example-context"])
    }

    @Test
    fun `an Off setting round-trips and keeps no fields`() = runBlocking<Unit> {
        loaded()

        PreferenceRepository.setMetricsSource("example-context", off)

        assertEquals(off, PreferenceRepository.metricsSourceByContext.value["example-context"])
    }

    @Test
    fun `the default setting removes the context instead of storing it`() = runBlocking<Unit> {
        loaded()
        PreferenceRepository.setMetricsSource("example-context", off)
        PreferenceRepository.setMetricsSource("example-context-2", gmp)

        PreferenceRepository.setMetricsSource("example-context", MetricsSourceSetting())

        assertFalse("example-context" in PreferenceRepository.metricsSourceByContext.value, "the default must not be stored")
        assertEquals(gmp, PreferenceRepository.metricsSourceByContext.value["example-context-2"], "another cluster's setting must survive")
    }

    @Test
    fun `removing the last setting leaves an empty map`() = runBlocking<Unit> {
        loaded()
        for (key in PreferenceRepository.metricsSourceByContext.value.keys) {
            PreferenceRepository.setMetricsSource(key, MetricsSourceSetting())
        }
        PreferenceRepository.setMetricsSource("example-context", off)

        PreferenceRepository.setMetricsSource("example-context", MetricsSourceSetting())

        assertEquals(emptyMap(), PreferenceRepository.metricsSourceByContext.value)
    }

    @Test
    fun `a setting made through a minted demo label is stored under the demo row`() = runBlocking<Unit> {
        loaded()

        PreferenceRepository.setMetricsSource(minted, off)

        assertEquals(off, PreferenceRepository.metricsSourceByContext.value[bare])
        assertFalse(minted in PreferenceRepository.metricsSourceByContext.value, "the raw minted label must not become a key")
        assertNull(PreferenceRepository.metricsSourceByContext.value["demo-cluster (mock) #7"])
    }

    @Test
    fun `every mode round-trips through the store`() = runBlocking<Unit> {
        loaded()

        for (setting in listOf(gmp, off, inCluster, inCluster.copy(mode = MetricsSourceMode.AUTO), gmp.copy(mode = MetricsSourceMode.AUTO), inCluster.copy(https = false, pathPrefix = ""))) {
            PreferenceRepository.setMetricsSource("example-context", setting)

            assertEquals(setting, PreferenceRepository.metricsSourceByContext.value["example-context"], "$setting")
            assertEquals(setting, MetricsSourceCodec.decode(dataStorePreferencesInstance.data.first()[storeKey])["example-context"], "$setting as stored")
        }
    }

    @Test
    fun `an In-cluster setting is stored as Auto plus a selected inCluster object`() = runBlocking<Unit> {
        loaded()

        PreferenceRepository.setMetricsSource("example-context", inCluster)

        val entry = storedEntries().getValue("example-context").jsonObject
        assertEquals("AUTO", entry.getValue("mode").jsonPrimitive.content)
        assertEquals("true", entry.getValue("inCluster").jsonObject.getValue("selected").jsonPrimitive.content)
    }

    @Test
    fun `strings v1_31_0 left in the store decode unchanged and survive a write for another key`() = runBlocking<Unit> {
        loaded()
        seedRaw(
            """{"example-context":{"mode":"GMP","project":"example-project","location":"us-central1","cluster":"example-cluster"},""" +
                """"example-context-2":{"mode":"OFF"}}""",
        )

        PreferenceRepository.setMetricsSource(bare, inCluster)

        assertEquals(mapOf("example-context" to gmp, "example-context-2" to off, bare to inCluster), PreferenceRepository.metricsSourceByContext.value)
        val entries = storedEntries()
        assertEquals(Json.parseToJsonElement("""{"mode":"OFF"}"""), entries.getValue("example-context-2"))
        assertEquals(
            Json.parseToJsonElement("""{"mode":"GMP","project":"example-project","location":"us-central1","cluster":"example-cluster"}"""),
            entries.getValue("example-context"),
        )
    }

    @Test
    fun `an entry with an unknown future mode survives a write for another key`() = runBlocking<Unit> {
        loaded()
        val future = """{"mode":"QUANTUM","project":"example-project","extra":{"n":7}}"""
        seedRaw("""{"future-context":$future,"example-context-2":{"mode":"OFF"}}""")

        PreferenceRepository.setMetricsSource("example-context", gmp)

        assertEquals(mapOf("example-context-2" to off, "example-context" to gmp), PreferenceRepository.metricsSourceByContext.value)
        assertEquals(Json.parseToJsonElement(future), storedEntries().getValue("future-context"), "the entry is written back as it was")
    }

    @Test
    fun `the entry with an unknown future mode stays when every decodable entry is reset`() = runBlocking<Unit> {
        loaded()
        seedRaw("""{"future-context":{"mode":"QUANTUM"}}""")
        PreferenceRepository.setMetricsSource("example-context", off)

        PreferenceRepository.setMetricsSource("example-context", MetricsSourceSetting())

        assertEquals(emptyMap(), PreferenceRepository.metricsSourceByContext.value)
        assertEquals(setOf("future-context"), storedEntries().keys)
    }

    @Test
    fun `a store that holds something other than a map is replaced by the next write`() = runBlocking<Unit> {
        loaded()
        seedRaw("not json")

        PreferenceRepository.setMetricsSource("example-context", off)

        assertEquals(mapOf("example-context" to off), PreferenceRepository.metricsSourceByContext.value)
        assertEquals(setOf("example-context"), storedEntries().keys)
    }
}
