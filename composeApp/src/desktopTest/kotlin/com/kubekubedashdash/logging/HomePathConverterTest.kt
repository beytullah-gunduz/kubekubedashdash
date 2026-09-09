package com.kubekubedashdash.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.encoder.Encoder
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every appender in the shipped logback.xml renders a home-directory path
 * as `~/…` (review follow-up F15): the console, the rolling file and the
 * in-app drawer. The test JVM's default logger context is configured from
 * that same file, so its encoders are asked to render a synthetic event —
 * rendering writes nothing — whose message and stack trace name a temp
 * directory the test points `user.home` at and restores afterwards.
 */
class HomePathConverterTest {

    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private lateinit var home: File
    private var previousHome: String? = null

    @BeforeTest
    fun setUp() {
        previousHome = System.getProperty("user.home")
        home = Files.createTempDirectory("kkdd-fake-home").toFile()
        System.setProperty("user.home", home.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        if (previousHome == null) System.clearProperty("user.home") else System.setProperty("user.home", previousHome)
        home.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun encoderOf(appender: Appender<ILoggingEvent>): Encoder<ILoggingEvent>? = when (appender) {
        is OutputStreamAppender<*> -> (appender as OutputStreamAppender<ILoggingEvent>).encoder
        is InMemoryAppender -> appender.encoder
        else -> null
    }

    private fun render(appenderName: String): String {
        val appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(appenderName) ?: context.getLogger("com.kubekubedashdash").getAppender(appenderName)
        assertNotNull(appender, "logback.xml declares $appenderName")
        val encoder = assertNotNull(encoderOf(appender), "$appenderName has an encoder")
        val logger = context.getLogger("com.kubekubedashdash.logging.HomePathConverterTest")
        val event = LoggingEvent(Logger::class.java.name, logger, Level.WARN, "found at ${home.absolutePath}/.kube/config", IllegalStateException("cannot read ${home.absolutePath}/.aws/credentials"), null)
        return String(encoder.encode(event))
    }

    @Test
    fun `every appender in the shipped configuration folds the home directory`() {
        for (name in listOf("CONSOLE", "FILE", "IN_MEMORY")) {
            val line = render(name)
            assertTrue(line.contains("found at ~/.kube/config"), "$name message: $line")
            assertTrue(line.contains("cannot read ~/.aws/credentials"), "$name stack trace: $line")
            assertFalse(line.contains(home.absolutePath), "$name still names the home directory: $line")
        }
    }
}
