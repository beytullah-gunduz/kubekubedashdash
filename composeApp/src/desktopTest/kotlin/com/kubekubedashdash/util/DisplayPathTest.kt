package com.kubekubedashdash.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A path under the user's home directory is rendered as `~/…` wherever the
 * app logs or shows it (review follow-up F8): the log pane and the
 * prerequisites modal are what users paste into bug reports, and an
 * absolute home path carries the account name. Anything else is left alone.
 */
class DisplayPathTest {

    private val sep = File.separator
    private val home = listOf("", "h", "u").joinToString(sep)

    @Test
    fun `a path under home starts with a tilde`() {
        assertEquals("~$sep.kube${sep}config", displayPath("$home$sep.kube${sep}config", home))
    }

    @Test
    fun `home itself is a tilde`() {
        assertEquals("~", displayPath(home, home))
    }

    @Test
    fun `a sibling directory that merely shares the prefix is not under home`() {
        val sibling = "${home}2$sep.kube${sep}config"
        assertEquals(sibling, displayPath(sibling, home))
    }

    @Test
    fun `a path outside home is unchanged`() {
        val outside = listOf("", "etc", "kube", "config").joinToString(sep)
        assertEquals(outside, displayPath(outside, home))
    }

    @Test
    fun `an unknown home leaves the path alone`() {
        val path = "$home$sep.kube${sep}config"
        assertEquals(path, displayPath(path, null))
        assertEquals(path, displayPath(path, ""))
        assertEquals(path, displayPath(path, "  "))
    }
}
