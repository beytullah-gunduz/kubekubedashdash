package com.kubekubedashdash

import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ThemeManager` reads the persisted mode once, at object init, which on a
 * cold launch is the default because the store is still loading; the saved
 * theme therefore never applied at launch (review follow-up F11). It now
 * learns the persisted mode through [ThemeManager.syncFromPreferences], which
 * `KubeDashTheme` is expected to feed from the repository's flow (that wiring
 * is not covered here: the build has no Compose UI-test dependency). The sync
 * must apply the
 * mode and the dark flag without writing anything back — a write-back would
 * loop through the very flow that feeds it. Runs only against the Gradle
 * test-data store; the manager's prior mode is restored after each case.
 */
class ThemeManagerSyncTest {

    private lateinit var original: ThemeMode
    private lateinit var originalPersisted: ThemeMode

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        original = ThemeManager.mode
        originalPersisted = PreferenceRepository.themeMode.value
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncFromPreferences(original)
        PreferenceRepository.setThemeMode(originalPersisted)
    }

    @Test
    fun `a persisted DARK lands in the manager without being written back`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        PreferenceRepository.setThemeMode(ThemeMode.LIGHT)

        ThemeManager.syncFromPreferences(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, ThemeManager.mode)
        assertTrue(ThemeManager.isDarkTheme)
        assertEquals(ThemeMode.LIGHT, PreferenceRepository.themeMode.value, "sync must never write the preference back")
    }

    @Test
    fun `a persisted LIGHT clears the dark flag`() {
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        ThemeManager.syncFromPreferences(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, ThemeManager.mode)
        assertFalse(ThemeManager.isDarkTheme)
    }

    @Test
    fun `a persisted SYSTEM leaves the dark flag to the system probe`() {
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        ThemeManager.syncFromPreferences(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, ThemeManager.mode)
        assertTrue(ThemeManager.isDarkTheme, "syncing SYSTEM must leave the flag the DARK sync set untouched")

        ThemeManager.applySystemDarkTheme(false)
        assertFalse(ThemeManager.isDarkTheme)
        ThemeManager.applySystemDarkTheme(true)
        assertTrue(ThemeManager.isDarkTheme)
    }

    @Test
    fun `setMode still persists, and the sync that follows it is a no-op`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        ThemeManager.setMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, PreferenceRepository.themeMode.value)

        ThemeManager.syncFromPreferences(PreferenceRepository.themeMode.value)
        assertEquals(ThemeMode.DARK, ThemeManager.mode)
        assertTrue(ThemeManager.isDarkTheme)
    }
}
