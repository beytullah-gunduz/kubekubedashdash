package com.kubekubedashdash.logging

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The log surface folds the home directory to `~` once, in free text, in
 * either separator style (review follow-up F15). An occurrence folds unless
 * the next character continues a name, so a sibling directory stays; nothing
 * is required before it. Every home here is a made-up string or a temp
 * directory the test owns; the real home is captured and restored, never
 * read into an assertion.
 */
class HomePathFoldingTest {

    private var previousHome: String? = null

    @BeforeTest
    fun setUp() {
        previousHome = System.getProperty("user.home")
    }

    @AfterTest
    fun tearDown() {
        if (previousHome == null) System.clearProperty("user.home") else System.setProperty("user.home", previousHome)
    }

    @Test
    fun `a path under home folds to a tilde path`() {
        assertEquals("found at ~/.kube/config", HomePathFolding.fold("found at /home/dev/.kube/config", home = "/home/dev"))
    }

    @Test
    fun `the home directory alone folds to a tilde`() {
        assertEquals("home is ~", HomePathFolding.fold("home is /home/dev", home = "/home/dev"))
        assertEquals("home is ~.", HomePathFolding.fold("home is /home/dev.", home = "/home/dev"))
        assertEquals("(~)", HomePathFolding.fold("(/home/dev)", home = "/home/dev"))
    }

    @Test
    fun `a sibling directory that merely starts with the home is left alone`() {
        val text = "/home/dev2/x /home/dev.bak /home/dev_old /home/dev-old /home/devops"
        assertEquals(text, HomePathFolding.fold(text, home = "/home/dev"))
    }

    @Test
    fun `a Windows home folds in both separator styles`() {
        assertEquals(
            "~\\.kube\\config and ~/.kube/config",
            HomePathFolding.fold("C:\\Profiles\\dev\\.kube\\config and C:/Profiles/dev/.kube/config", home = "C:\\Profiles\\dev"),
        )
    }

    @Test
    fun `every occurrence in a line folds`() {
        assertEquals("from ~/a to ~/b", HomePathFolding.fold("from /home/dev/a to /home/dev/b", home = "/home/dev"))
    }

    @Test
    fun `a trailing separator on the home is ignored`() {
        assertEquals("~/x", HomePathFolding.fold("/home/dev/x", home = "/home/dev/"))
        assertEquals("~\\x", HomePathFolding.fold("C:\\Profiles\\dev\\x", home = "C:\\Profiles\\dev\\"))
    }

    @Test
    fun `no home means no change`() {
        assertEquals("/home/dev/x", HomePathFolding.fold("/home/dev/x", home = null))
        assertEquals("/home/dev/x", HomePathFolding.fold("/home/dev/x", home = " "))
        assertEquals("/home/dev/x", HomePathFolding.fold("/home/dev/x", home = "/"))
    }

    @Test
    fun `text without the home is returned unchanged`() {
        assertEquals("nothing to fold here", HomePathFolding.fold("nothing to fold here", home = "/home/dev"))
    }

    @Test
    fun `folding is idempotent`() {
        val once = HomePathFolding.fold("from /home/dev/a to /home/dev/b", home = "/home/dev")
        assertEquals(once, HomePathFolding.fold(once, home = "/home/dev"))
    }

    @Test
    fun `the default home is the user home property`() {
        val home = Files.createTempDirectory("kkdd-fake-home").toFile()
        try {
            System.setProperty("user.home", home.absolutePath)
            assertEquals("found at ~/.kube/config", HomePathFolding.fold("found at ${home.absolutePath}/.kube/config"))
            System.setProperty("user.home", "/home/other")
            assertEquals("found at ~/y", HomePathFolding.fold("found at /home/other/y"), "a changed home is picked up")
        } finally {
            home.deleteRecursively()
        }
    }
}
