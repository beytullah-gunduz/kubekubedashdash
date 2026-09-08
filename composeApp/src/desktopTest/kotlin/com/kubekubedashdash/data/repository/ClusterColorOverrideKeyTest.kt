package com.kubekubedashdash.data.repository

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The repository keys cluster colours by [DemoContext.preferenceKey], like
 * every other per-cluster store since N4: a colour set through a minted demo
 * label lands on the one demo row, and a later mint clears the same row
 * (review follow-up F11). Runs only against the Gradle test-data store.
 */
class ClusterColorOverrideKeyTest {

    private val bare = DemoContext.MOCK_CONTEXT_NAME
    private val minted = "demo-cluster (mock) #3"
    private val reminted = "demo-cluster (mock) #7"

    @BeforeTest
    fun refuseRealDataDirectory() {
        kotlin.test.assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @Test
    fun `a colour set under a minted demo label is stored under the demo key and cleared through another mint`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        PreferenceRepository.setClusterColor(minted, "#123456")
        assertEquals("#123456", PreferenceRepository.clusterColorOverrides.value[bare])
        assertFalse(minted in PreferenceRepository.clusterColorOverrides.value, "the raw minted label must not become a key")

        PreferenceRepository.clearClusterColor(reminted)
        assertNull(PreferenceRepository.clusterColorOverrides.value[bare], "a later mint must clear the same row")
    }

    @Test
    fun `a real context is stored under its own name`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        PreferenceRepository.setClusterColor("example-context", "#654321")
        assertEquals("#654321", PreferenceRepository.clusterColorOverrides.value["example-context"])
        PreferenceRepository.clearClusterColor("example-context")
        assertNull(PreferenceRepository.clusterColorOverrides.value["example-context"])
    }
}
