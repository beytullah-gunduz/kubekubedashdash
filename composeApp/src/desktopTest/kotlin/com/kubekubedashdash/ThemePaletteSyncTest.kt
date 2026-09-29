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
 * `ThemePalette` is orthogonal to `ThemeMode` and `ThemeStyle` (D1). `ThemeManager.palette` follows
 * the same F11 shape as `style`: [ThemeManager.syncPaletteFromPreferences] applies a persisted
 * palette without writing it back, so `KubeDashTheme`'s effect can feed it from the repository's
 * flow without looping through it. Runs only against the Gradle test-data store; the manager's prior
 * mode, style and palette are restored after each case.
 */
class ThemePaletteSyncTest {

    private lateinit var originalPalette: ThemePalette
    private lateinit var originalPersistedPalette: ThemePalette
    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true
    private val paletteKey = stringPreferencesKey("theme_palette")

    // setThemePalette persists from an un-awaited launch. Wait for the file to show the value before
    // moving on, so no write is still in flight when the next case — or the next test class — touches
    // the same key. Every call site below issues a real `setThemePalette` write first (never relies on
    // the key's absence), so this can require the literal string: it must not treat a still-absent key
    // as an already-satisfied STYLE, or the "must never touch the file" case below would race the
    // async write it is supposed to wait out.
    private fun awaitPersisted(palette: ThemePalette) = runBlocking {
        awaitStoredPreference(paletteKey) { it == palette.name }
    }

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalPalette = ThemeManager.palette
        originalPersistedPalette = PreferenceRepository.themePalette.value
        originalStyle = ThemeManager.style
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
        ThemeManager.syncPaletteFromPreferences(originalPalette)
        PreferenceRepository.setThemePalette(originalPersistedPalette)
        awaitPersisted(originalPersistedPalette)
    }

    @Test
    fun `a persisted HIGH_CONTRAST lands in the manager without being written back`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        PreferenceRepository.setThemePalette(ThemePalette.STYLE)
        awaitPersisted(ThemePalette.STYLE)

        ThemeManager.syncPaletteFromPreferences(ThemePalette.HIGH_CONTRAST)

        assertEquals(ThemePalette.HIGH_CONTRAST, ThemeManager.palette)
        assertEquals(ThemePalette.STYLE, PreferenceRepository.themePalette.value, "sync must never write the preference back")
        val onDisk = dataStorePreferencesInstance.data.first()[paletteKey]
        assertEquals(ThemePalette.STYLE.name, onDisk, "sync must never touch the file either")
    }

    @Test
    fun `setPalette still persists, and the sync that follows it is a no-op`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        ThemeManager.setPalette(ThemePalette.MONOCHROME)
        assertEquals(ThemePalette.MONOCHROME, PreferenceRepository.themePalette.value)
        awaitPersisted(ThemePalette.MONOCHROME)

        ThemeManager.syncPaletteFromPreferences(PreferenceRepository.themePalette.value)
        assertEquals(ThemePalette.MONOCHROME, ThemeManager.palette)
    }

    @Test
    fun `palette is independent of mode and style`() {
        ThemeManager.syncPaletteFromPreferences(ThemePalette.HIGH_CONTRAST)

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertEquals(ThemePalette.HIGH_CONTRAST, ThemeManager.palette, "RETRO must not touch the palette")
        assertTrue(ThemeManager.isRetro, "a palette must not turn the style off")

        ThemeManager.syncFromPreferences(ThemeMode.LIGHT)
        assertEquals(ThemePalette.HIGH_CONTRAST, ThemeManager.palette, "LIGHT must not touch the palette")
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        assertEquals(ThemePalette.HIGH_CONTRAST, ThemeManager.palette, "DARK must not touch the palette")

        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        assertEquals(ThemePalette.HIGH_CONTRAST, ThemeManager.palette, "DEFAULT must not touch the palette")
        assertFalse(ThemeManager.isRetro)

        // And the other way round: changing the palette leaves style and mode where they were.
        ThemeManager.syncPaletteFromPreferences(ThemePalette.MONOCHROME)
        assertEquals(ThemeStyle.DEFAULT, ThemeManager.style)
        assertEquals(ThemeMode.DARK, ThemeManager.mode)
        assertTrue(ThemeManager.isDarkTheme)
    }
}
