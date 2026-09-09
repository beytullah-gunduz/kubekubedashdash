package com.kubekubedashdash.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.LoggingEvent
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
 * The entries the in-app drawer keeps carry no home-directory path in any
 * field (review follow-up F15): the rendered line, the raw message and the
 * throwable message are folded by the appender itself, with or without an
 * encoder, so no reader of an AppLogEntry depends on the configured pattern.
 * The appender under test is a fresh instance fed a synthetic event; the
 * home is a temp directory the test points `user.home` at and restores.
 */
class InMemoryAppenderFoldingTest {

    private val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    private lateinit var home: File
    private var previousHome: String? = null

    @BeforeTest
    fun setUp() {
        previousHome = System.getProperty("user.home")
        // The shared store is fed by the real drawer appender for the whole suite; start from empty.
        AppLogStore.clear()
        home = Files.createTempDirectory("kkdd-fake-home").toFile()
        System.setProperty("user.home", home.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        if (previousHome == null) System.clearProperty("user.home") else System.setProperty("user.home", previousHome)
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun appendAndFetch(appender: InMemoryAppender): AppLogEntry {
        val marker = "f15-" + System.nanoTime()
        val logger = loggerContext.getLogger("com.kubekubedashdash.logging.InMemoryAppenderFoldingTest")
        val event = LoggingEvent(Logger::class.java.name, logger, Level.WARN, "$marker found at ${home.absolutePath}/.kube/config", IllegalStateException("cannot read ${home.absolutePath}/.aws/credentials"), null)
        appender.doAppend(event)
        appender.stop()
        return AppLogStore.entries.value.first { it.message.startsWith(marker) }
    }

    @Test
    fun `the entries kept for the drawer carry no home path in any field`() {
        val appender = InMemoryAppender().apply {
            context = loggerContext
            start()
        }

        val entry = appendAndFetch(appender)

        assertEquals("found at ~/.kube/config", entry.message.substringAfter(" "))
        assertEquals("found at ~/.kube/config", entry.formattedMessage.substringAfter(" "))
        assertEquals("cannot read ~/.aws/credentials", entry.throwable)
    }

    @Test
    fun `a rendered line is folded by the appender even when its pattern does not fold`() {
        val encoder = PatternLayoutEncoder().apply {
            context = loggerContext
            pattern = "%-5level %msg"
            start()
        }
        val appender = InMemoryAppender().apply {
            context = loggerContext
            this.encoder = encoder
            start()
        }

        val entry = appendAndFetch(appender)

        // With no throwable converter in the pattern, logback appends the stack
        // trace right after the message, on the same line.
        val marker = entry.message.substringBefore(" ")
        assertTrue(entry.formattedMessage.startsWith("WARN  $marker found at ~/.kube/config"), "got: ${entry.formattedMessage}")
        assertFalse(entry.formattedMessage.contains(home.absolutePath), "the appended stack trace still names the home directory")
    }
}
