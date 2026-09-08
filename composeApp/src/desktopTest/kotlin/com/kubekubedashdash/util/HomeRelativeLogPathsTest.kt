package com.kubekubedashdash.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The prerequisite check and the kubeconfig locator render paths under the
 * home directory as `~/…` in their log lines and in the modal's detail
 * (review follow-up F8). The test points `user.home` and the `kubeconfig`
 * property at a temp directory it owns — never the real home — and restores
 * both afterwards. The checker's other checks run as they normally would.
 */
class HomeRelativeLogPathsTest {

    private lateinit var home: File
    private var previousHome: String? = null
    private var previousKubeconfig: String? = null

    private val checkerLogger = LoggerFactory.getLogger(PrerequisiteChecker::class.java) as Logger
    private val locatorLogger = LoggerFactory.getLogger(KubeconfigLocator::class.java) as Logger
    private val checkerEvents = ListAppender<ILoggingEvent>().apply { start() }
    private val locatorEvents = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("kkdd-fake-home").toFile()
        previousHome = System.getProperty("user.home")
        previousKubeconfig = System.getProperty("kubeconfig")
        System.setProperty("user.home", home.absolutePath)
        checkerLogger.addAppender(checkerEvents)
        locatorLogger.addAppender(locatorEvents)
    }

    @AfterTest
    fun tearDown() {
        checkerLogger.detachAppender(checkerEvents)
        locatorLogger.detachAppender(locatorEvents)
        checkerEvents.stop()
        locatorEvents.stop()
        restore("user.home", previousHome)
        restore("kubeconfig", previousKubeconfig)
        home.deleteRecursively()
    }

    private fun restore(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    private fun kubeconfigCheck(): PrerequisiteCheck = PrerequisiteChecker.runAll().checks.first { it.name == PrerequisiteChecker.NAME_KUBECONFIG }

    private fun messages(events: ListAppender<ILoggingEvent>): List<Pair<Level, String>> = events.list.map { it.level to it.formattedMessage }

    @Test
    fun `a kubeconfig found under home is shown and logged as a tilde path`() {
        val config = File(home, ".kube${File.separator}config").apply {
            parentFile.mkdirs()
            writeText("apiVersion: v1\nkind: Config\ncontexts: []\n")
        }
        System.setProperty("kubeconfig", config.absolutePath)

        val check = kubeconfigCheck()

        assertEquals(CheckStatus.PASSED, check.status, "detail: ${check.detail}")
        assertEquals("~${File.separator}.kube${File.separator}config", check.detail)
        val found = messages(checkerEvents).filter { it.second.startsWith("Kubeconfig found at") }
        assertEquals(1, found.size, "one DEBUG line, got: ${messages(checkerEvents)}")
        assertEquals(Level.DEBUG, found.single().first)
        assertTrue(found.single().second.contains("~${File.separator}.kube"), "got: ${found.single().second}")
        assertFalse(messages(checkerEvents).any { it.second.contains(home.absolutePath) }, "the absolute home path must not be logged: ${messages(checkerEvents)}")
    }

    @Test
    fun `a missing kubeconfig under home is reported and logged as a tilde path`() {
        val missing = File(home, ".kube${File.separator}missing")
        System.setProperty("kubeconfig", missing.absolutePath)

        val check = kubeconfigCheck()

        assertEquals(CheckStatus.FAILED, check.status)
        assertEquals("Not found at ~${File.separator}.kube${File.separator}missing", check.detail)
        val warned = messages(checkerEvents).filter { it.second.startsWith("Kubeconfig not found at") }
        assertEquals(1, warned.size, "one WARN line, got: ${messages(checkerEvents)}")
        assertEquals(Level.WARN, warned.single().first)
        assertTrue(warned.single().second.contains("~${File.separator}.kube${File.separator}missing"), "got: ${warned.single().second}")
        assertFalse(messages(checkerEvents).any { it.second.contains(home.absolutePath) }, "the absolute home path must not be logged: ${messages(checkerEvents)}")
    }

    @Test
    fun `creating the kubeconfig parent directory logs it as a tilde path`() {
        val config = File(home, ".kube${File.separator}config")

        KubeconfigLocator.ensureParentDirectory(config.absolutePath)

        assertTrue(config.parentFile.isDirectory, "the parent directory is created")
        val created = messages(locatorEvents).filter { it.second.startsWith("Creating kubeconfig parent directory") }
        assertEquals(listOf(Level.INFO to "Creating kubeconfig parent directory ~${File.separator}.kube"), created)
    }
}
