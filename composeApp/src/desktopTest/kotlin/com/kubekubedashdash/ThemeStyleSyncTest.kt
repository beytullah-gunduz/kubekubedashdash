package com.kubekubedashdash

import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubekubedashdash.data.datastore.dataStorePreferencesInstance
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ThemeStyle` is orthogonal to `ThemeMode` (D1). `ThemeManager.style` follows
 * the same F11 shape as `mode`: [ThemeManager.syncStyleFromPreferences] applies
 * a persisted style without writing it back, so `KubeDashTheme`'s effect can
 * feed it from the repository's flow without looping through it. Runs only
 * against the Gradle test-data store; the manager's prior mode and style are
 * restored after each case.
 */
class ThemeStyleSyncTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalPersistedStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true
    private val styleKey = stringPreferencesKey("theme_style")

    // setThemeStyle persists from an un-awaited launch. Wait for the file to
    // show the value before moving on, so no write is still in flight when
    // the next case — or the next test class — touches the same key. Every
    // call site below issues a real `setThemeStyle` write first (never relies
    // on the key's absence), so this can require the literal string — unlike
    // ThemeManagerSyncTest's matcher, it must not treat a still-absent key as
    // an already-satisfied DEFAULT, or the "must never touch the file" case
    // below would race the async write it is supposed to wait out.
    private fun awaitPersisted(style: ThemeStyle) = runBlocking {
        awaitStoredPreference(styleKey) { it == style.name }
    }

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalPersistedStyle = PreferenceRepository.themeStyle.value
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
        PreferenceRepository.setThemeStyle(originalPersistedStyle)
        awaitPersisted(originalPersistedStyle)
    }

    @Test
    fun `a persisted RETRO lands in the manager without being written back`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        PreferenceRepository.setThemeStyle(ThemeStyle.DEFAULT)
        awaitPersisted(ThemeStyle.DEFAULT)

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)

        assertEquals(ThemeStyle.RETRO, ThemeManager.style)
        assertTrue(ThemeManager.isRetro)
        assertEquals(ThemeStyle.DEFAULT, PreferenceRepository.themeStyle.value, "sync must never write the preference back")
        val onDisk = dataStorePreferencesInstance.data.first()[styleKey]
        assertEquals(ThemeStyle.DEFAULT.name, onDisk, "sync must never touch the file either")
    }

    @Test
    fun `setStyle still persists, and the sync that follows it is a no-op`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        ThemeManager.setStyle(ThemeStyle.RETRO)
        assertEquals(ThemeStyle.RETRO, PreferenceRepository.themeStyle.value)
        awaitPersisted(ThemeStyle.RETRO)

        ThemeManager.syncStyleFromPreferences(PreferenceRepository.themeStyle.value)
        assertEquals(ThemeStyle.RETRO, ThemeManager.style)
        assertTrue(ThemeManager.isRetro)
    }

    @Test
    fun `isRetro is true only for RETRO, independent of mode`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        assertTrue(ThemeManager.isRetro, "RETRO + DARK")
        ThemeManager.syncFromPreferences(ThemeMode.LIGHT)
        assertTrue(ThemeManager.isRetro, "RETRO + LIGHT")
        ThemeManager.syncFromPreferences(ThemeMode.SYSTEM)
        assertTrue(ThemeManager.isRetro, "RETRO + SYSTEM")

        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        assertFalse(ThemeManager.isRetro, "DEFAULT + SYSTEM")
    }
}
