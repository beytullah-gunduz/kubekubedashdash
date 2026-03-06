package com.kubedash.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.kubedash.data.datastore.dataStorePreferencesInstance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

object PreferenceRepository {
    private val dataStore: DataStore<Preferences> by lazy { dataStorePreferencesInstance }

    private val DARK_THEME by lazy { booleanPreferencesKey("dark_theme") }

    var darkTheme: Boolean
        get() = runBlocking { dataStore.data.firstOrNull()?.get(DARK_THEME) ?: true }
        set(value) {
            runBlocking {
                dataStore.edit {
                    it[DARK_THEME] = value
                }
            }
        }

    fun darkTheme(): Flow<Boolean> = dataStore.data.map {
        it[DARK_THEME] ?: true
    }
}
