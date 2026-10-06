package com.kubekubedashdash.util

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.FileAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With `kkdd.dataDir` set (the Gradle test, screenshot and hot-run tasks), the logs go to a
 * `logs` directory inside it, so none of those JVMs appends to the developer's real app.log.
 * Unset or blank (production), every platform keeps its path. The platform cases resolve
 * against a fake home the test owns, and nothing here changes a system property.
 */
class SystemDirectoriesLogsDirTest {

    private lateinit var home: File

    @BeforeTest
    fun setUp() {
        // The real path, so the under-home check is not fooled by a symlinked temp root (macOS /var).
        home = Files.createTempDirectory("kkdd-fake-home").toRealPath().toFile()
    }

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun logsDir(dataDirProperty: String?, os: String, env: Map<String, String> = emptyMap()): String = SystemDirectories.resolveLogsDir(dataDirProperty, os, home.path, env::get)

    @Test
    fun `an overridden data directory holds the logs on every platform`() {
        val dataDir = File(home, "data").path
        val env = mapOf("XDG_STATE_HOME" to File(home, "state").path, "LOCALAPPDATA" to File(home, "local").path)
        for (os in listOf("Mac OS X", "Linux", "Windows 11")) {
            assertEquals(File(dataDir, "logs").path, logsDir(dataDir, os, env), os)
        }
    }

    @Test
    fun `a missing or blank override keeps the macOS path`() {
        for (property in listOf(null, "", "  ")) {
            assertEquals("${home.path}/Library/Logs/KubeKubeDashDash", logsDir(property, "Mac OS X"), "property: '$property'")
        }
    }

    @Test
    fun `a missing or blank override keeps the Linux path`() {
        for (property in listOf(null, "", "  ")) {
            assertEquals("${home.path}/.local/state/kubekubedashdash/logs", logsDir(property, "Linux"), "property: '$property'")
        }
        val xdg = File(home, "state").path
        assertEquals("$xdg/kubekubedashdash/logs", logsDir("", "Linux", mapOf("XDG_STATE_HOME" to xdg)))
        val outsideHome = File(home.parentFile, "elsewhere-" + home.name).path
        assertEquals(
            "${home.path}/.local/state/kubekubedashdash/logs",
            logsDir("", "Linux", mapOf("XDG_STATE_HOME" to outsideHome)),
            "an XDG_STATE_HOME outside home falls back to the default under home",
        )
    }

    @Test
    fun `a missing or blank override keeps the Windows path`() {
        for (property in listOf(null, "", "  ")) {
            assertEquals("${home.path}\\AppData\\Local\\KubeKubeDashDash\\Logs", logsDir(property, "Windows 11"), "property: '$property'")
        }
    }

    @Test
    fun `under the Gradle test task logback writes the app log inside the test data directory`() {
        // Checked before either lazy directory is touched, so a run without the seam creates nothing real.
        val property = System.getProperty("kkdd.dataDir")
        assertTrue(!property.isNullOrBlank() && property.contains("test-data"), "refusing to run outside the Gradle test task")
        val logs = File(property, "logs").path

        assertEquals(logs, SystemDirectories.logsDirectory)
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val file = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as FileAppender<*>
        assertEquals(File(logs, "app.log").absolutePath, File(file.file).absolutePath)
    }
}
