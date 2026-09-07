package com.kubekubedashdash.data.datastore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settings › Diagnostics row text is a pure function of the recorded faults. */
class PreferenceStorageStateTest {

    @Test
    fun `no fault is healthy and says so`() {
        val state = PreferenceStorageState()
        assertTrue(state.healthy)
        assertEquals(HEALTHY_SUMMARY, state.summary())
    }

    @Test
    fun `a replaced file names its copy and nothing else`() {
        val backup = "settings_preferences.preferences_pb.corrupt-20260907-101500"
        val text = PreferenceStorageState(load = LoadFault("CorruptionException", backup)).summary()
        assertTrue(text.contains(backup), text)
        assertTrue(text.contains("defaults are in use"), text)
        assertFalse(text.contains("CorruptionException"), "the copy's name is the useful fact, not the class: $text")
        assertFalse(text.contains("/") || text.contains("\\"), "never a path: $text")
    }

    @Test
    fun `an unreadable store names the exception class`() {
        val text = PreferenceStorageState(load = LoadFault("AccessDeniedException")).summary()
        assertTrue(text.contains("AccessDeniedException"), text)
        assertTrue(text.contains("defaults are in use"), text)
    }

    @Test
    fun `a failed save is its own sentence, alone or after a load fault`() {
        val saveOnly = PreferenceStorageState(save = SaveFault("IOException")).summary()
        assertTrue(saveOnly.contains("IOException"), saveOnly)
        assertTrue(saveOnly.contains("may be lost"), saveOnly)
        assertFalse(saveOnly.contains("defaults are in use"), saveOnly)

        val both = PreferenceStorageState(load = LoadFault("IOException"), save = SaveFault("IOException")).summary()
        assertTrue(both.contains("defaults are in use") && both.contains("may be lost"), both)
        assertTrue(both.indexOf("defaults are in use") < both.indexOf("may be lost"), "load first, then save: $both")
    }
}
