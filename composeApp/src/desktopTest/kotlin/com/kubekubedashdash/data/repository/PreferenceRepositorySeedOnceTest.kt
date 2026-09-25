package com.kubekubedashdash.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.data.datastore.dataStorePreferencesInstance
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.awaitStoredPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PreferenceRepository` seeds its flows from DataStore ONCE. Its collector
 * re-fires on every commit — including unrelated keys and other repositories'
 * keys — and it used to overwrite every scalar flow from disk each time, so
 * two quick setters (A then B, each writing its flow synchronously and
 * persisting from an unordered launch) let A's commit re-seed B's flow with
 * the stale persisted value until B's own commit landed: a visible flip-back
 * on rapid toggles. After the first emission the in-memory flow is
 * authoritative; the mutators that persist first (cluster colours, default
 * namespaces, pinned resources) now update memory themselves.
 *
 * Runs only against the Gradle test-data store (`kkdd.dataDir`), never the
 * developer's real preferences file.
 */
class PreferenceRepositorySeedOnceTest {

    private val themeKey = stringPreferencesKey("theme_mode")

    @BeforeTest
    fun refuseRealDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @Test
    fun `a commit behind the repository's back never overwrites a value set in memory`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        PreferenceRepository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, PreferenceRepository.themeMode.value)
        awaitStoredPreference(themeKey) { it == "DARK" }

        // What a racing setter's stale emission looks like: the file says
        // LIGHT while memory says DARK.
        dataStorePreferencesInstance.edit { it[themeKey] = "LIGHT" }
        awaitStoredPreference(themeKey) { it == "LIGHT" }
        delay(1_000)
        assertEquals(ThemeMode.DARK, PreferenceRepository.themeMode.value, "memory is authoritative after the first seed")
    }

    @Test
    fun `mutators that persist first still update memory before they return`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        PreferenceRepository.setClusterColor("example-context", "#123456")
        assertEquals("#123456", PreferenceRepository.clusterColorOverrides.value["example-context"])
        PreferenceRepository.clearClusterColor("example-context")
        assertNull(PreferenceRepository.clusterColorOverrides.value["example-context"])
        PreferenceRepository.setDefaultNamespace("example-context", "team-a")
        assertEquals("team-a", PreferenceRepository.defaultNamespaceByContext.value["example-context"])
        PreferenceRepository.clearDefaultNamespace("example-context")
        assertNull(PreferenceRepository.defaultNamespaceByContext.value["example-context"])
        PreferenceRepository.togglePinned("pin-a")
        assertTrue("pin-a" in PreferenceRepository.pinnedResources.value)
        PreferenceRepository.togglePinned("pin-a")
        assertFalse("pin-a" in PreferenceRepository.pinnedResources.value)
    }
}
