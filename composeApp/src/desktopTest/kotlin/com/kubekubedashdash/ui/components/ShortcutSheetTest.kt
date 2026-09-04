package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the pure shortcut-table model in ShortcutSheet.kt (WS1, D5):
 * every group is populated, no accelerator repeats within a group, the two
 * platform renderings agree row-for-row, and the sheet documents its own
 * `⌘/` binding.
 */
class ShortcutSheetTest {

    private val macGroups = appShortcuts(mac = true)
    private val otherGroups = appShortcuts(mac = false)

    @Test
    fun `every group is non-empty and no row is blank`() {
        for (groups in listOf(macGroups, otherGroups)) {
            assertTrue(groups.isNotEmpty())
            groups.forEach { group ->
                assertTrue(group.shortcuts.isNotEmpty(), "group '${group.title}' has no shortcuts")
                group.shortcuts.forEach { shortcut ->
                    assertTrue(shortcut.keys.isNotBlank(), "blank keys in group '${group.title}'")
                    assertTrue(shortcut.action.isNotBlank(), "blank action in group '${group.title}'")
                }
            }
        }
    }

    @Test
    fun `no accelerator is listed twice within a group`() {
        for (groups in listOf(macGroups, otherGroups)) {
            groups.forEach { group ->
                val keys = group.shortcuts.map { it.keys }
                assertEquals(keys.distinct(), keys, "duplicate accelerator within group '${group.title}'")
            }
        }
    }

    @Test
    fun `mac and non-mac forms agree on which rows carry a modifier`() {
        macGroups.zip(otherGroups).forEach { (macGroup, otherGroup) ->
            macGroup.shortcuts.zip(otherGroup.shortcuts).forEach { (macShortcut, otherShortcut) ->
                val macHasCmd = macShortcut.keys.contains("⌘")
                val otherHasCtrl = otherShortcut.keys.contains("Ctrl+")
                assertEquals(macHasCmd, otherHasCtrl, "modifier mismatch for action '${macShortcut.action}'")
            }
        }
    }

    @Test
    fun `the two platform lists agree index-for-index on group title and action`() {
        assertEquals(macGroups.map { it.title }, otherGroups.map { it.title })
        macGroups.zip(otherGroups).forEach { (macGroup, otherGroup) ->
            assertEquals(
                macGroup.shortcuts.map { it.action },
                otherGroup.shortcuts.map { it.action },
                "action mismatch in group '${macGroup.title}'",
            )
        }
    }

    @Test
    fun `the Global group documents its own toggle`() {
        val global = macGroups.first { it.title == "Global" }
        assertTrue(global.shortcuts.any { it.keys == "⌘/" && it.action == "Show the shortcut sheet" })

        val globalOther = otherGroups.first { it.title == "Global" }
        assertTrue(globalOther.shortcuts.any { it.keys == "Ctrl+/" && it.action == "Show the shortcut sheet" })
    }
}
