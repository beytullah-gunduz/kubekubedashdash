package com.kubekubedashdash.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rolling file log keeps INFO and above from the app's own loggers: the app's DEBUG lines
 * (namespaces, object names) reach the in-app drawer only, and library lines (API paths, traces) never
 * reach the file. Runs against the logback.xml the app ships, whose FILE appender the Gradle
 * test task points at build/test-data/logs; the test refuses any other file.
 */
class FileLogThresholdTest {

    @Test
    fun `the file log keeps an app INFO line and drops an app DEBUG line`() {
        val file = guardedLogFile()

        val marker = UUID.randomUUID().toString()
        val log = LoggerFactory.getLogger("com.kubekubedashdash.logging.FileLogThresholdTest")
        log.debug("threshold-debug-{}", marker)
        log.info("threshold-info-{}", marker)

        val text = file.readText()
        assertTrue("threshold-info-$marker" in text, "an INFO line must reach the file log")
        assertFalse("threshold-debug-$marker" in text, "a DEBUG line must stay out of the file log")
    }

    @Test
    fun `the file log drops a library WARN line and keeps an app WARN line`() {
        val file = guardedLogFile()

        val marker = UUID.randomUUID().toString()
        LoggerFactory.getLogger("io.fabric8.test.Probe").warn("library-warn-{}", marker)
        LoggerFactory.getLogger("com.kubekubedashdash.logging.FileLogThresholdTest").warn("app-warn-{}", marker)

        val text = file.readText()
        assertTrue("app-warn-$marker" in text, "an app WARN line must reach the file log")
        assertFalse("library-warn-$marker" in text, "a library WARN line must stay out of the file log")
    }

    private fun guardedLogFile(): File {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as? FileAppender<*>
        assertNotNull(appender, "logback.xml has no FILE appender on the root logger")
        val file = File(appender.file)
        assertTrue(file.path.contains("test-data"), "refusing to read a log file outside the Gradle test-data directory")
        return file
    }
}
