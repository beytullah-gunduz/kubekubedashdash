package com.kubekubedashdash.services.session

import com.kubekubedashdash.data.datastore.PreferenceStorageState

/** Why launch did, or did not, rebuild the last session. */
sealed interface RestoreDecision {
    data object Restore : RestoreDecision

    /** The user turned "restore last session" off. */
    data object TurnedOff : RestoreDecision

    /** The preference store had not been read within the launch wait; its value is the compile-time default, not the user's. */
    data object PreferencesNotRead : RestoreDecision

    /** The store could not be read, or was replaced after a parse failure (see PreferenceStorageHealth); same rule. */
    data class PreferencesFaulted(val exceptionClass: String) : RestoreDecision
}

/**
 * Launch rebuilds the last session only when the preference was actually
 * read. Its compile-time default is ON, so acting on it when the store was
 * not read in time, or could not be read at all, would override a user who
 * turned restore off. A save fault says nothing about what was read.
 */
internal fun decideRestore(
    preferencesLoaded: Boolean,
    storage: PreferenceStorageState,
    restoreOnLaunch: Boolean,
): RestoreDecision = when {
    !preferencesLoaded -> RestoreDecision.PreferencesNotRead
    storage.load != null -> RestoreDecision.PreferencesFaulted(storage.load.exceptionClass)
    !restoreOnLaunch -> RestoreDecision.TurnedOff
    else -> RestoreDecision.Restore
}
