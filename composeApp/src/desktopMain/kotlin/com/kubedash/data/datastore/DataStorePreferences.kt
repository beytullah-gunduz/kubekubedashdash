package com.kubedash.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.kubedash.util.SystemDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toPath

private const val PREFERENCE_DATASTORE = "settings_preferences.preferences_pb"

val dataStorePreferencesInstance: DataStore<Preferences> by lazy {
    PreferenceDataStoreFactory.createWithPath(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = {
            (SystemDirectories.applicationDirectory + "/" + PREFERENCE_DATASTORE).toPath()
        },
    )
}
