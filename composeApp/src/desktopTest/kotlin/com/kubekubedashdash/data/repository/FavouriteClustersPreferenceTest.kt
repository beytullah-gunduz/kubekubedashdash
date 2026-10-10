package com.kubekubedashdash.data.repository

import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.awaitStoredPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cluster-picker favourites round-trip through the repository: starring appends in order, the
 * stored blob holds both keys, and unstarring removes them. Runs only against the Gradle
 * test-data store, and leaves its two keys unstarred whatever else the store holds.
 */
class FavouriteClustersPreferenceTest {

    private val favA = "example-fav-a"
    private val favB = "example-fav-b"

    @BeforeTest
    fun refuseRealDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private suspend fun unstarOurs() {
        listOf(favA, favB)
            .filter { it in PreferenceRepository.favouriteClusters.value }
            .forEach { PreferenceRepository.toggleFavouriteCluster(it) }
    }

    @Test
    fun `starring two clusters stores them in order and unstarring removes them`() = runBlocking<Unit> {
        withTimeout(10_000) { PreferenceRepository.preferencesLoaded.first { it } }

        try {
            unstarOurs()

            PreferenceRepository.toggleFavouriteCluster(favA)
            PreferenceRepository.toggleFavouriteCluster(favB)
            assertEquals(listOf(favA, favB), PreferenceRepository.favouriteClusters.value.takeLast(2))

            awaitStoredPreference(stringPreferencesKey("favourite_clusters")) { raw ->
                StringListCodec.decode(raw).containsAll(listOf(favA, favB))
            }

            PreferenceRepository.toggleFavouriteCluster(favA)
            assertFalse(favA in PreferenceRepository.favouriteClusters.value)
            PreferenceRepository.toggleFavouriteCluster(favB)
            assertFalse(favB in PreferenceRepository.favouriteClusters.value)
        } finally {
            unstarOurs()
        }
    }
}
