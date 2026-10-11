package com.kubekubedashdash.data.repository

import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * every key it touched.
 */
class MetricsSourcePreferenceTest {

    private val bare = DemoContext.MOCK_CONTEXT_NAME
    private val minted = "demo-cluster (mock) #3"
    private val gmp = MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster")
    private val off = MetricsSourceSetting(MetricsSourceMode.OFF)

    @BeforeTest
    fun refuseRealDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @AfterTest
    fun restoreDefaults() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        for (context in listOf("example-context", "example-context-2", bare, minted)) {
            PreferenceRepository.setMetricsSource(context, MetricsSourceSetting())
        }
    }

    private suspend fun loaded() {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
    }

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
}
