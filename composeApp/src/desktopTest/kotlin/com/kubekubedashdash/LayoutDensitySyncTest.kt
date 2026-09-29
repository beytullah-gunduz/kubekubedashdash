package com.kubekubedashdash

import androidx.compose.ui.unit.dp
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
 * WS8 (D6): the app-wide density. It is stored under the historical `table_density` key with the
 * historical values, so a choice made while it was table-only carries over. It follows the same
 * F11 shape as the other theme axes: [ThemeManager.syncLayoutDensityFromPreferences] applies the
 * persisted value without writing it back, so `KubeDashTheme`'s effect can feed it from the
 * repository's flow without looping through it. Only the persistence cases write, and they write
 * to the Gradle test-data store and restore the original value.
 */
class LayoutDensitySyncTest {

    private val densityKey = stringPreferencesKey("table_density")
    private lateinit var originalDensity: LayoutDensity
    private lateinit var originalPersisted: LayoutDensity

    // setLayoutDensity persists from an un-awaited launch. Wait for the file to show the value
    // before moving on, so no write is still in flight when the next case — or the next test
    // class — touches the same key (the known DataStore mid-write-collector race). Every call
    // site issues a real write first, so this requires the literal string rather than treating
    // an absent key as an already-satisfied default.
    private fun awaitPersisted(value: LayoutDensity) = runBlocking {
        awaitStoredPreference(densityKey) { it == value.key }
    }

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalDensity = ThemeManager.layoutDensity
        originalPersisted = PreferenceRepository.layoutDensity.value
        ThemeManager.syncLayoutDensityFromPreferences(LayoutDensity.COMFORTABLE)
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncLayoutDensityFromPreferences(originalDensity)
        PreferenceRepository.setLayoutDensity(originalPersisted)
        awaitPersisted(originalPersisted)
    }

    @Test
    fun `the storage keys are the historical table density values`() {
        assertEquals("comfortable", LayoutDensity.COMFORTABLE.key)
        assertEquals("compact", LayoutDensity.COMPACT.key)
    }

    @Test
    fun `a persisted compact lands in the manager without being written back`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        PreferenceRepository.setLayoutDensity(LayoutDensity.COMFORTABLE)
        awaitPersisted(LayoutDensity.COMFORTABLE)

        ThemeManager.syncLayoutDensityFromPreferences(LayoutDensity.COMPACT)

        assertEquals(LayoutDensity.COMPACT, ThemeManager.layoutDensity)
        assertTrue(ThemeManager.isCompact)
        assertEquals(
            LayoutDensity.COMFORTABLE,
            PreferenceRepository.layoutDensity.value,
            "sync must never write the preference back",
        )
        val onDisk = dataStorePreferencesInstance.data.first()[densityKey]
        assertEquals("comfortable", onDisk, "sync must never touch the file either")
    }

    @Test
    fun `setLayoutDensity persists`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        PreferenceRepository.setLayoutDensity(LayoutDensity.COMPACT)
        awaitPersisted(LayoutDensity.COMPACT)

        assertEquals(LayoutDensity.COMPACT, PreferenceRepository.layoutDensity.value)
        assertEquals("compact", dataStorePreferencesInstance.data.first()[densityKey])
    }

    @Test
    fun `ThemeManager setLayoutDensity persists, flips isCompact, and the sync that follows is a no-op`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        assertFalse(ThemeManager.isCompact)

        ThemeManager.setLayoutDensity(LayoutDensity.COMPACT)
        assertEquals(LayoutDensity.COMPACT, ThemeManager.layoutDensity)
        assertTrue(ThemeManager.isCompact)
        assertEquals(LayoutDensity.COMPACT, PreferenceRepository.layoutDensity.value)
        awaitPersisted(LayoutDensity.COMPACT)

        ThemeManager.syncLayoutDensityFromPreferences(PreferenceRepository.layoutDensity.value)
        assertTrue(ThemeManager.isCompact)

        ThemeManager.setLayoutDensity(LayoutDensity.COMFORTABLE)
        assertFalse(ThemeManager.isCompact)
        awaitPersisted(LayoutDensity.COMFORTABLE)
    }

    @Test
    fun `orCompact picks the compact value only in Compact`() {
        assertEquals(8.dp, 8.dp.orCompact(4.dp), "Comfortable keeps the receiver")

        ThemeManager.syncLayoutDensityFromPreferences(LayoutDensity.COMPACT)
        assertEquals(4.dp, 8.dp.orCompact(4.dp), "Compact takes the argument")
        assertEquals(0.dp, 1.dp.orCompact(0.dp))

        ThemeManager.syncLayoutDensityFromPreferences(LayoutDensity.COMFORTABLE)
        assertEquals(8.dp, 8.dp.orCompact(4.dp))
    }
}
