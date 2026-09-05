package com.kubekubedashdash.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsIndexTest {

    // The rail order is data (SettingsSectionOrder) shared with the screen, so
    // this holds the registry to the real rail rather than to a copy. What it
    // cannot see is the Column's SettingsSection sequence — that agreement is
    // checked by eye (plan §5, rail order).
    private val knownSections = SettingsSectionOrder.toSet()

    @Test
    fun `the rail order has twelve distinct sections`() {
        assertEquals(12, SettingsSectionOrder.size)
        assertEquals(SettingsSectionOrder.size, SettingsSectionOrder.toSet().size)
    }

    @Test
    fun `no blank title, section or keyword`() {
        SettingsEntries.forEach { entry ->
            assertTrue(entry.section.isNotBlank(), "blank section for title '${entry.title}'")
            assertTrue(entry.title.isNotBlank(), "blank title in section '${entry.section}'")
            entry.keywords.forEach { keyword ->
                assertTrue(keyword.isNotBlank(), "blank keyword on '${entry.title}'")
            }
        }
    }

    @Test
    fun `section and title pairs are unique`() {
        val pairs = SettingsEntries.map { it.section to it.title }
        assertEquals(pairs.size, pairs.toSet().size, "duplicate (section, title) pair among $pairs")
    }

    @Test
    fun `every entry section is a known rail title`() {
        SettingsEntries.forEach { entry ->
            assertTrue(entry.section in knownSections, "unknown section '${entry.section}'")
        }
    }

    @Test
    fun `blank query returns no results`() {
        assertEquals(emptyList(), settingsSearchResults(""))
        assertEquals(emptyList(), settingsSearchResults("   "))
    }

    @Test
    fun `zoom finds UI zoom first by title`() {
        val results = settingsSearchResults("zoom")
        assertEquals("UI zoom", results.first().title)
    }

    @Test
    fun `font finds UI zoom by keyword`() {
        val results = settingsSearchResults("font")
        assertTrue(results.any { it.title == "UI zoom" })
    }

    @Test
    fun `mask finds Secret values`() {
        val results = settingsSearchResults("mask")
        assertTrue(results.any { it.title == "Secret values" })
    }

    @Test
    fun `colour finds Cluster colors`() {
        val results = settingsSearchResults("colour")
        assertTrue(results.any { it.title == "Cluster colors" })
    }

    @Test
    fun `density finds Table density`() {
        val results = settingsSearchResults("density")
        assertTrue(results.any { it.title == "Table density" })
    }

    @Test
    fun `refresh finds Topology auto-refresh`() {
        val results = settingsSearchResults("refresh")
        assertTrue(results.any { it.title == "Topology auto-refresh" })
    }

    @Test
    fun `namespace finds Default namespace`() {
        val results = settingsSearchResults("namespace")
        assertTrue(results.any { it.title == "Default namespace" })
    }

    @Test
    fun `title match outranks keyword match outranks section match`() {
        // "tab" is a title substring of "Tab strip", a keyword of
        // "Cluster colors", and a section substring (via "Tab behavior")
        // that only "Restore last session" reaches, since the other two
        // rows in that section already rank as title matches.
        val results = settingsSearchResults("tab")
        val titleRank = results.indexOfFirst { it.title == "Tab strip" }
        val keywordRank = results.indexOfFirst { it.title == "Cluster colors" }
        val sectionRank = results.indexOfFirst { it.title == "Restore last session" }
        assertTrue(titleRank >= 0 && keywordRank >= 0 && sectionRank >= 0)
        assertTrue(titleRank < keywordRank)
        assertTrue(keywordRank < sectionRank)
    }

    @Test
    fun `search is case insensitive`() {
        assertEquals(settingsSearchResults("zoom"), settingsSearchResults("ZOOM"))
    }

    @Test
    fun `tab returns no entry twice`() {
        val results = settingsSearchResults("tab")
        assertEquals(results.size, results.distinct().size)
    }
}
