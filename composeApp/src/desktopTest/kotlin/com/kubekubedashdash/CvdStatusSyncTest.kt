package com.kubekubedashdash

import androidx.datastore.preferences.core.booleanPreferencesKey
import com.kubekubedashdash.data.datastore.dataStorePreferencesInstance
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.theme.DefaultDarkColors
import com.kubekubedashdash.theme.DefaultDarkCvd
import com.kubekubedashdash.theme.kdPaletteSpec
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * WS6 (D5): the colour-blind-safe status switch (`cvd_safe_status`). It follows the same F11 shape
 * as style and palette: [ThemeManager.syncCvdFromPreferences] applies the persisted value without
 * writing it back, so `KubeDashTheme`'s effect can feed it from the repository's flow without
 * looping through it. The switch replaces only the status quartet and `onError` (D5), never the
 * syntax colours (D14). Every switch goes through a `sync*` — never a `set*` — except the two
 * persistence cases, which write to the Gradle test-data store and restore the original value.
 */
class CvdStatusSyncTest {

    private val cvdKey = booleanPreferencesKey("cvd_safe_status")
    private var originalCvd = false
    private var originalPersisted = false
    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true
    private lateinit var originalPalette: ThemePalette

    // setCvdSafeStatus persists from an un-awaited launch. Wait for the file to show the value
    // before moving on, so no write is still in flight when the next case — or the next test
    // class — touches the same key (the known DataStore mid-write-collector race). Every call
    // site issues a real write first, so this requires the literal value rather than treating an
    // absent key as an already-satisfied `false`.
    private fun awaitPersisted(value: Boolean) = runBlocking {
        awaitStoredPreference(cvdKey) { it == value }
    }

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalCvd = ThemeManager.cvdSafeStatus
        originalPersisted = PreferenceRepository.cvdSafeStatus.value
        originalStyle = ThemeManager.style
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
        originalPalette = ThemeManager.palette
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        ThemeManager.syncPaletteFromPreferences(ThemePalette.STYLE)
        ThemeManager.syncCvdFromPreferences(false)
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
        ThemeManager.syncPaletteFromPreferences(originalPalette)
        ThemeManager.syncCvdFromPreferences(originalCvd)
        PreferenceRepository.setCvdSafeStatus(originalPersisted)
        awaitPersisted(originalPersisted)
    }

    @Test
    fun `a persisted true lands in the manager without being written back`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        PreferenceRepository.setCvdSafeStatus(false)
        awaitPersisted(false)

        ThemeManager.syncCvdFromPreferences(true)

        assertTrue(ThemeManager.cvdSafeStatus)
        assertFalse(PreferenceRepository.cvdSafeStatus.value, "sync must never write the preference back")
        val onDisk = dataStorePreferencesInstance.data.first()[cvdKey]
        assertEquals(false, onDisk, "sync must never touch the file either")
    }

    @Test
    fun `setCvdSafeStatus persists`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        PreferenceRepository.setCvdSafeStatus(true)
        awaitPersisted(true)

        assertTrue(PreferenceRepository.cvdSafeStatus.value)
    }

    @Test
    fun `ThemeManager setCvdSafeStatus persists, and the sync that follows it is a no-op`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        ThemeManager.setCvdSafeStatus(true)
        assertTrue(ThemeManager.cvdSafeStatus)
        assertTrue(PreferenceRepository.cvdSafeStatus.value)
        awaitPersisted(true)

        ThemeManager.syncCvdFromPreferences(PreferenceRepository.cvdSafeStatus.value)
        assertTrue(ThemeManager.cvdSafeStatus)
    }

    @Test
    fun `the switch is independent of mode, style and palette`() {
        ThemeManager.syncCvdFromPreferences(true)

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertTrue(ThemeManager.cvdSafeStatus, "RETRO must not touch the switch")
        ThemeManager.syncFromPreferences(ThemeMode.LIGHT)
        assertTrue(ThemeManager.cvdSafeStatus, "LIGHT must not touch the switch")
        ThemeManager.syncPaletteFromPreferences(ThemePalette.NORD)
        assertTrue(ThemeManager.cvdSafeStatus, "a palette change must not touch the switch")

        // And the other way round: flipping the switch leaves style, mode and palette where they were.
        ThemeManager.syncCvdFromPreferences(false)
        assertEquals(ThemeStyle.RETRO, ThemeManager.style)
        assertEquals(ThemeMode.LIGHT, ThemeManager.mode)
        assertEquals(ThemePalette.NORD, ThemeManager.palette)
    }

    @Test
    fun `switched on under Default dark, the status colours change and the syntax colours do not`() {
        val syntaxOff = listOf(KdSyntaxKey, KdSyntaxString, KdSyntaxNumber, KdSyntaxBool, KdSyntaxComment)
        assertEquals(DefaultDarkColors.success, KdSuccess)
        assertEquals(DefaultDarkColors.error, KdError)

        ThemeManager.syncCvdFromPreferences(true)

        assertEquals(DefaultDarkCvd.success, KdSuccess)
        assertEquals(DefaultDarkCvd.warning, KdWarning)
        assertEquals(DefaultDarkCvd.error, KdError)
        assertEquals(DefaultDarkCvd.info, KdInfo)
        assertEquals(DefaultDarkCvd.onError, KdOnError)
        assertEquals(DefaultDarkCvd.error, ThemeManager.scheme.error, "the M3 error role follows the switch")
        assertEquals(DefaultDarkCvd.onError, ThemeManager.scheme.onError)
        assertNotEquals(DefaultDarkColors.success, KdSuccess)

        val syntaxOn = listOf(KdSyntaxKey, KdSyntaxString, KdSyntaxNumber, KdSyntaxBool, KdSyntaxComment)
        assertEquals(syntaxOff, syntaxOn, "the switch must never change a syntax colour (D14)")
        assertEquals(DefaultDarkColors.syntaxString, KdSyntaxString)
    }

    @Test
    fun `switched on, every palette resolves its own colour-blind status set`() {
        for (dark in listOf(true, false)) {
            for (palette in ThemePalette.entries) {
                ThemeManager.syncFromPreferences(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
                ThemeManager.syncPaletteFromPreferences(palette)
                val variant = kdPaletteSpec(ThemeStyle.DEFAULT, palette).variant(dark)
                val cvd = variant.cvd
                val label = "$palette dark=$dark"

                ThemeManager.syncCvdFromPreferences(true)
                assertEquals(cvd.success, KdSuccess, "success, $label")
                assertEquals(cvd.warning, KdWarning, "warning, $label")
                assertEquals(cvd.error, KdError, "error, $label")
                assertEquals(cvd.info, KdInfo, "info, $label")
                assertEquals(cvd.onError, KdOnError, "onError, $label")
                assertEquals(cvd.error, ThemeManager.scheme.error, "scheme.error, $label")

                ThemeManager.syncCvdFromPreferences(false)
                assertEquals(variant.colors.error, KdError, "cvd off, $label")
            }
        }
    }
}
