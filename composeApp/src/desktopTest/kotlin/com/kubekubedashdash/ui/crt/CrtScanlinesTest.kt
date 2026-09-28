package com.kubekubedashdash.ui.crt

import androidx.compose.ui.graphics.toPixelMap
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.awaitStoredPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WS4 (D13): the "CRT scanlines" switch and its static overlay.
 *
 * [scanlineTile] is pure (WS4 "Tile construction") — it pins the exact per-mode alphas so the
 * scanline pitch and darkness never silently drift. The preference case mirrors the other
 * boolean-preference setters (e.g. `setLogDrawerBesideSidebar`): it runs only against the Gradle
 * test-data store and restores the original persisted value afterwards.
 */
class CrtScanlinesTest {

    private val scanlinesKey = booleanPreferencesKey("crt_scanlines")
    private var originalPersisted = false

    // setCrtScanlines persists from an un-awaited launch. Wait for the file to show the value
    // before moving on, so no write is still in flight when the next test — or the next test
    // class — touches the same key (the known DataStore mid-write-collector race).
    private fun awaitPersisted(value: Boolean) = runBlocking {
        awaitStoredPreference(scanlinesKey) { it == value }
    }

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalPersisted = PreferenceRepository.crtScanlines.value
    }

    @AfterTest
    fun restore() {
        PreferenceRepository.setCrtScanlines(originalPersisted)
        awaitPersisted(originalPersisted)
    }

    @Test
    fun `setCrtScanlines persists`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        PreferenceRepository.setCrtScanlines(true)
        awaitPersisted(true)

        assertTrue(PreferenceRepository.crtScanlines.value)
    }

    @Test
    fun `scanlineTile rows 0 and 1 are transparent, row 2 is black at 0,14 alpha (retro-dark)`() {
        assertTileAlphas(0.14f)
    }

    @Test
    fun `scanlineTile rows 0 and 1 are transparent, row 2 is black at 0,08 alpha (retro-light)`() {
        assertTileAlphas(0.08f)
    }

    private fun assertTileAlphas(alpha: Float) {
        val pixels = scanlineTile(alpha).toPixelMap()

        val row0 = pixels[0, 0]
        val row1 = pixels[0, 1]
        assertEquals(0f, row0.alpha, "row 0 must be fully transparent")
        assertEquals(0f, row1.alpha, "row 1 must be fully transparent")

        val row2 = pixels[0, 2]
        assertEquals(0f, row2.red, "row 2 must be black (red)")
        assertEquals(0f, row2.green, "row 2 must be black (green)")
        assertEquals(0f, row2.blue, "row 2 must be black (blue)")
        assertTrue(
            abs(row2.alpha - alpha) <= 1f / 255f,
            "row 2 alpha ${row2.alpha} not within 8-bit rounding of $alpha",
        )
    }
}
