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
    fun `a forward slash after a backslash-separated home is still the boundary`() {
        // KubeconfigLocator builds its fallback with a forward slash; on Windows
        // that follows a backslash-separated home.
        assertEquals("~/.kube/config", displayPath("C:\\Users\\alice/.kube/config", "C:\\Users\\alice"))
        assertEquals("C:\\Users\\alice2/.kube/config", displayPath("C:\\Users\\alice2/.kube/config", "C:\\Users\\alice"))
    }

    @Test
    fun `a trailing separator on home is ignored`() {
        assertEquals("~$sep.kube", displayPath("$home$sep.kube", "$home$sep"))
    }

    @Test
    fun `a root home folds nothing`() {
        val etc = listOf("", "etc", "x").joinToString(sep)
        assertEquals(etc, displayPath(etc, sep))
    }

    @Test
    fun `an unknown home leaves the path alone`() {
        val path = "$home$sep.kube${sep}config"
        assertEquals(path, displayPath(path, null))
        assertEquals(path, displayPath(path, ""))
        assertEquals(path, displayPath(path, "  "))
    }
}
