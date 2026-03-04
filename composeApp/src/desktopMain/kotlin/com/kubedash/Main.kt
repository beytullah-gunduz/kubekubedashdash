package com.kubedash

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import com.kubedash.logging.InMemoryAppender
import org.slf4j.LoggerFactory

private fun configureLogging() {
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    loggerContext.reset()

    val consoleEncoder = PatternLayoutEncoder().apply {
        context = loggerContext
        pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %-36.36logger{36} - %msg%n%throwable"
        start()
    }
    val consoleAppender = ConsoleAppender<ILoggingEvent>().apply {
        context = loggerContext
        name = "CONSOLE"
        encoder = consoleEncoder
        start()
    }

    val memoryEncoder = PatternLayoutEncoder().apply {
        context = loggerContext
        pattern = "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{24} - %msg%throwable"
        start()
    }
    val inMemoryAppender = InMemoryAppender().apply {
        context = loggerContext
        name = "IN_MEMORY"
        maxEntries = 500
        encoder = memoryEncoder
        start()
    }

    loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).apply {
        level = Level.INFO
        addAppender(consoleAppender)
        addAppender(inMemoryAppender)
    }

    loggerContext.getLogger("com.kubedash").level = Level.DEBUG

    loggerContext.getLogger("io.fabric8").level = Level.WARN
    loggerContext.getLogger("okhttp3").level = Level.WARN
    loggerContext.getLogger("io.netty").level = Level.WARN
}

fun main() {
    configureLogging()

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val appIcon = BitmapPainter(useResource("icon.png", ::loadImageBitmap))

        Window(
            onCloseRequest = ::exitApplication,
            title = "KubeKubeDashDash",
            state = windowState,
            icon = appIcon,
            undecorated = true,
        ) {
            App(
                windowScope = this,
                windowState = windowState,
                onClose = ::exitApplication,
            )
        }
    }
}
