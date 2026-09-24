package com.kubekubedashdash.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogTabBadgesTest {

    @Test
    fun `one cluster tab gets no badges`() {
        val badges = logTabBadges(mapOf("s1" to "alpha")) { Color.Red }
        assertTrue(badges.isEmpty())
    }

    @Test
    fun `two cluster tabs are both badged with their context and colour`() {
        val badges = logTabBadges(mapOf("s1" to "alpha", "s2" to "beta")) { ctx ->
            if (ctx == "alpha") Color.Red else Color.Blue
        }
        assertEquals(LogTabBadge("alpha", Color.Red), badges["s1"])
        assertEquals(LogTabBadge("beta", Color.Blue), badges["s2"])
    }

    @Test
    fun `a tab whose context is still blank is skipped but still counts`() {
        val badges = logTabBadges(mapOf("s1" to "alpha", "s2" to "")) { Color.Red }
        assertEquals(1, badges.size)
        assertEquals(LogTabBadge("alpha", Color.Red), badges["s1"])
    }
}
