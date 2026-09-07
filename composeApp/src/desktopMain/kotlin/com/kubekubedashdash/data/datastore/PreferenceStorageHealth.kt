package com.kubekubedashdash.data.datastore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What Settings › Diagnostics says about the preferences store. A launch-time
 * read failure and a later save failure are independent facts, so both are
 * kept. Never carries a path: the data folder is under the user's home.
 */
data class PreferenceStorageState(
    val load: LoadFault? = null,
    val save: SaveFault? = null,
) {
    val healthy: Boolean get() = load == null && save == null
}

/**
 * The store could not be read when the repositories seeded. [backupFileName]
 * is the copy kept next to a file that could not be parsed and was replaced
 * with defaults; null when the store was unreadable for another reason, or
 * the copy could not be made — the file is then untouched.
 */
data class LoadFault(
    val exceptionClass: String,
    val backupFileName: String? = null,
)

/** The most recent failed save. */
data class SaveFault(val exceptionClass: String)

internal const val HEALTHY_SUMMARY = "Settings are read from and saved to the application data folder normally."

/** The Settings row text: one sentence per fault, load first. File names and class names only. */
fun PreferenceStorageState.summary(): String {
    if (healthy) return HEALTHY_SUMMARY
    return listOfNotNull(
        load?.let { fault ->
            if (fault.backupFileName != null) {
                "The saved settings file could not be parsed, so defaults are in use. " +
                    "A copy was kept next to it as ${fault.backupFileName}."
            } else {
                "The saved settings could not be read (${fault.exceptionClass}), " +
                    "so defaults are in use for this session."
            }
        },
        save?.let { fault ->
            "The last attempt to save settings failed (${fault.exceptionClass}). " +
                "Changes made in this session may be lost."
        },
    ).joinToString(" ")
}

/**
 * Record of preference-storage faults, written by the DataStore corruption
 * handler, the repositories' read recovery and their write scopes; read by
 * Settings. The first load fault of a run wins — the three repositories share
 * one store and report the same failure — and the latest save fault wins.
 * The process instance is [Default]; tests build their own.
 */
class PreferenceStorageHealth {
    private val _state = MutableStateFlow(PreferenceStorageState())
    val state: StateFlow<PreferenceStorageState> = _state.asStateFlow()

    fun reportLoadFault(fault: LoadFault) {
        _state.update { current -> if (current.load == null) current.copy(load = fault) else current }
    }

    fun reportSaveFault(fault: SaveFault) {
        _state.update { it.copy(save = fault) }
    }

    companion object {
        val Default = PreferenceStorageHealth()
    }
}
