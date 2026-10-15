package com.kubekubedashdash.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.spi.FilterReply
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLoggersOnlyFilterTest {

    private val context = LoggerContext()
    private val filter = AppLoggersOnlyFilter()

    private fun decide(loggerName: String): FilterReply {
        val logger = context.getLogger(loggerName)
        return filter.decide(LoggingEvent(Logger::class.java.name, logger, Level.WARN, "probe", null, null))
    }

    @Test
    fun `the app's own loggers pass`() {
        assertEquals(FilterReply.NEUTRAL, decide("com.kubekubedashdash"))
        assertEquals(FilterReply.NEUTRAL, decide("com.kubekubedashdash.util.X"))
        assertEquals(FilterReply.NEUTRAL, decide("com.kubekubedashdash.UncaughtException"))
    }

    @Test
    fun `library loggers and look-alike names are denied`() {
        assertEquals(FilterReply.DENY, decide("io.fabric8.kubernetes.client.informers.impl.cache.Reflector"))
        assertEquals(FilterReply.DENY, decide("okhttp3.X"))
        assertEquals(FilterReply.DENY, decide("io.netty.X"))
        assertEquals(FilterReply.DENY, decide("com.kubekubedashdashx.Y"))
        assertEquals(FilterReply.DENY, decide("com.kubekubedash"))
        assertEquals(FilterReply.DENY, decide(Logger.ROOT_LOGGER_NAME))
    }
}
