package com.kubekubedashdash.services.session

import com.kubekubedashdash.data.datastore.LoadFault
import com.kubekubedashdash.data.datastore.PreferenceStorageState
import com.kubekubedashdash.data.datastore.SaveFault
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Launch rebuilds the last session only when the "restore last session"
 * preference was actually read. The compile-time default is ON, so acting
 * on it when the store was not read in time, or could not be read at all,
 * would override a user who turned restore off — which is exactly what
 * happened when the preference read was given up on (review follow-up F12).
 * Pure decision; no store, no windows.
 */
class RestoreGateTest {

    private val healthy = PreferenceStorageState()

    @Test
    fun `preferences read and restore on`() {
        assertEquals(RestoreDecision.Restore, decideRestore(preferencesLoaded = true, storage = healthy, restoreOnLaunch = true))
    }

    @Test
    fun `preferences read and restore off`() {
        assertEquals(RestoreDecision.TurnedOff, decideRestore(preferencesLoaded = true, storage = healthy, restoreOnLaunch = false))
    }

    @Test
    fun `preferences not read in time never restore on the compile-time default`() {
        assertEquals(RestoreDecision.PreferencesNotRead, decideRestore(preferencesLoaded = false, storage = healthy, restoreOnLaunch = true))
        assertEquals(RestoreDecision.PreferencesNotRead, decideRestore(preferencesLoaded = false, storage = healthy, restoreOnLaunch = false))
    }

    @Test
    fun `a store that could not be read never restores, even though it loaded on defaults`() {
        val unreadable = PreferenceStorageState(load = LoadFault("AccessDeniedException"))

        assertEquals(
            RestoreDecision.PreferencesFaulted("AccessDeniedException"),
            decideRestore(preferencesLoaded = true, storage = unreadable, restoreOnLaunch = true),
        )
    }

    @Test
    fun `a store that was replaced after a parse failure never restores either`() {
        val replaced = PreferenceStorageState(load = LoadFault("CorruptionException", "settings.corrupt-1"))

        assertEquals(
            RestoreDecision.PreferencesFaulted("CorruptionException"),
            decideRestore(preferencesLoaded = true, storage = replaced, restoreOnLaunch = true),
        )
    }

    @Test
    fun `a save fault says nothing about what was read`() {
        val saveOnly = PreferenceStorageState(save = SaveFault("IOException"))

        assertEquals(RestoreDecision.Restore, decideRestore(preferencesLoaded = true, storage = saveOnly, restoreOnLaunch = true))
        assertEquals(RestoreDecision.TurnedOff, decideRestore(preferencesLoaded = true, storage = saveOnly, restoreOnLaunch = false))
    }

    @Test
    fun `not read outranks a fault, a fault outranks the preference`() {
        val faulted = PreferenceStorageState(load = LoadFault("IOException"))

        assertEquals(RestoreDecision.PreferencesNotRead, decideRestore(preferencesLoaded = false, storage = faulted, restoreOnLaunch = true))
        assertEquals(RestoreDecision.PreferencesFaulted("IOException"), decideRestore(preferencesLoaded = true, storage = faulted, restoreOnLaunch = false))
    }
}
