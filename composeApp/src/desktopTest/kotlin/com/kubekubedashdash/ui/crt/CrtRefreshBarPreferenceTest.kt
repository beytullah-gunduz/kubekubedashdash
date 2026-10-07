package com.kubekubedashdash.ui.crt

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.awaitStoredPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "CRT refresh bar" settings persist (the mode by enum name, plus the Keep-rolling switch),
 * mirroring [CrtScanlinesTest]: they run only against the Gradle test-data store and restore the
 * original values afterwards.
 */
class CrtRefreshBarPreferenceTest {

    private val refreshBarKey = stringPreferencesKey("crt_refresh_bar")
    private val backgroundKey = booleanPreferencesKey("crt_refresh_bar_background")
    private var originalPersisted = CrtRefreshBarMode.OFF
    private var originalBackground = false

    // setCrtRefreshBar persists from an un-awaited launch. Wait for the file to show the value so
    // no write is still in flight when the next test touches the same key.
    private fun awaitPersisted(value: CrtRefreshBarMode) = runBlocking {
        awaitStoredPreference(refreshBarKey) { it == value.name }
    }

    private fun awaitBackgroundPersisted(value: Boolean) = runBlocking {
        awaitStoredPreference(backgroundKey) { it == value }
    }

    @BeforeTest
    fun setUp() {
        check(SystemDirectories.dataDirectory.contains("test-data")) {
            "refusing to run against a data directory that is not the Gradle test-data directory"
        }
        originalPersisted = PreferenceRepository.crtRefreshBar.value
        originalBackground = PreferenceRepository.crtRefreshBarBackground.value
    }

    @AfterTest
    fun restore() {
        PreferenceRepository.setCrtRefreshBar(originalPersisted)
        awaitPersisted(originalPersisted)
        PreferenceRepository.setCrtRefreshBarBackground(originalBackground)
        awaitBackgroundPersisted(originalBackground)
    }

    @Test
    fun `setCrtRefreshBar persists the mode name`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        PreferenceRepository.setCrtRefreshBar(CrtRefreshBarMode.ROLLING)
        awaitPersisted(CrtRefreshBarMode.ROLLING)

        assertEquals(CrtRefreshBarMode.ROLLING, PreferenceRepository.crtRefreshBar.value)
    }

    @Test
    fun `setCrtRefreshBarBackground persists`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        PreferenceRepository.setCrtRefreshBarBackground(true)
        awaitBackgroundPersisted(true)

        assertTrue(PreferenceRepository.crtRefreshBarBackground.value)
    }
}
