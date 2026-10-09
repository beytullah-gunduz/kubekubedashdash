package com.kubekubedashdash.ui

import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.add_filled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The "Apply YAML…" command-palette entry: its texts, its group, and that the `>` prefix reaches it. */
class PaletteApplyYamlEntryTest {

    @Test
    fun `the entry carries the texts, group and icon the palette shows`() {
        val entry = applyYamlPaletteEntry {}

        assertEquals("action:apply-yaml", entry.id)
        assertEquals("Apply YAML…", entry.label)
        assertEquals("create or update resources from a manifest", entry.sublabel)
        assertEquals("Actions", entry.category)
        assertEquals(Res.drawable.add_filled, entry.icon)
    }

    @Test
    fun `activating the entry opens the window`() {
        var opened = 0

        applyYamlPaletteEntry { opened++ }.onActivate()

        assertEquals(1, opened)
    }

    @Test
    fun `the actions prefix reaches the entry's group`() {
        val categories = categoriesForPrefix(">")

        assertTrue(categories != null && applyYamlPaletteEntry {}.category in categories)
    }

    @Test
    fun `the entry sits in the Actions group with the namespace log entries, under one header`() {
        val capture = applyYamlPaletteEntry {}.copy(id = "action:capture:example-ns", label = "Capture logs: example-ns", sublabel = null)

        val rows = paletteRows(listOf(applyYamlPaletteEntry {}, capture))

        assertEquals(3, rows.size)
        assertEquals(PaletteRow.Header("Actions"), rows[0])
    }
}
