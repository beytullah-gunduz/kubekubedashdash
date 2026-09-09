package com.kubekubedashdash.logging

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Edge cases the result audit of F15 raised: a drive root as home would
 * have rewritten every `C:` in a line; a POSIX home needs no backslash
 * form, and matching one only widened the scan; a sibling whose name
 * continues with a non-ASCII letter is still a sibling.
 */
class HomePathFoldingEdgeTest {

    @Test
    fun `a home without a separator folds nothing`() {
        val text = "C:\\Profiles\\dev\\x and 'C:' alone"
        assertEquals(text, HomePathFolding.fold(text, home = "C:\\"))
        assertEquals(text, HomePathFolding.fold(text, home = "C:"))
    }

    @Test
    fun `a POSIX home is matched only as written`() {
        assertEquals("~/x and \\home\\dev\\x", HomePathFolding.fold("/home/dev/x and \\home\\dev\\x", home = "/home/dev"))
    }

    @Test
    fun `a Windows home is matched with either separator`() {
        assertEquals("~/x and ~\\x", HomePathFolding.fold("C:/Profiles/dev/x and C:\\Profiles\\dev\\x", home = "C:\\Profiles\\dev"))
    }

    @Test
    fun `a sibling that continues with a non-ASCII letter stays`() {
        assertEquals("/home/dev\u00e9/x /home/dev\u00fc", HomePathFolding.fold("/home/dev\u00e9/x /home/dev\u00fc", home = "/home/dev"))
    }
}
