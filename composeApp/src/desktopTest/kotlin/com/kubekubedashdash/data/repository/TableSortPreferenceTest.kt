package com.kubekubedashdash.data.repository

import androidx.datastore.preferences.core.stringPreferencesKey
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
 * The per-table sort preference: [PreferenceRepository.setTableSort] updates the flow before it
 * returns and persists the same blob [StringMapCodec] reads back; null drops the entry. Runs only
 * against the Gradle test-data store, under table keys no screen uses, and clears both of them
 * afterwards — the repository is shared by every test class in the JVM.
 */
class TableSortPreferenceTest {

    private val keyOne = "SortPersistenceTest"
    private val keyTwo = "SortPersistenceTest-2"
    private val storeKey = stringPreferencesKey("table_sort")

    @BeforeTest
    fun refuseRealDataDirectory() = runBlocking<Unit> {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        // A removal made before the seed would be undone by it.
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }
        PreferenceRepository.setTableSort(keyOne, null)
        PreferenceRepository.setTableSort(keyTwo, null)
    }

    @AfterTest
    fun clearSorts() {
        // The teardown runs even when the guard above failed; it must not touch a real store then.
        if (!SystemDirectories.dataDirectory.contains("test-data")) return
        PreferenceRepository.setTableSort(keyOne, null)
        PreferenceRepository.setTableSort(keyTwo, null)
    }

    @Test
    fun `a stored sort is visible in memory at once and lands in the store`() = runBlocking<Unit> {
        PreferenceRepository.setTableSort(keyOne, "asc:Name")

        assertEquals("asc:Name", PreferenceRepository.tableSorts.value[keyOne])
        awaitStoredPreference(storeKey) { StringMapCodec.decode(it)[keyOne] == "asc:Name" }
    }

    @Test
    fun `clearing one table's sort drops only its entry`() = runBlocking<Unit> {
        PreferenceRepository.setTableSort(keyOne, "asc:Name")
        PreferenceRepository.setTableSort(keyTwo, "desc:Age")
        awaitStoredPreference(storeKey) {
            val m = StringMapCodec.decode(it)
            m[keyOne] == "asc:Name" && m[keyTwo] == "desc:Age"
        }

        PreferenceRepository.setTableSort(keyOne, null)

        assertFalse(keyOne in PreferenceRepository.tableSorts.value)
        assertEquals("desc:Age", PreferenceRepository.tableSorts.value[keyTwo])
        awaitStoredPreference(storeKey) {
            val m = StringMapCodec.decode(it)
            keyOne !in m && keyTwo in m
        }
    }
}
