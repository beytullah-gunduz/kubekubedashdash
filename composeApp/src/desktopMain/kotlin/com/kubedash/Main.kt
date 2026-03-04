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
import java.util.concurrent.TimeUnit

/**
 * macOS GUI apps launched from Finder/Dock inherit a minimal PATH (/usr/bin:/bin:/usr/sbin:/sbin)
 * that doesn't include user-installed tools like `aws`, `gcloud`, etc. which kubeconfig exec
 * plugins need. Resolve the real PATH from the user's login shell.
 */
private fun inheritShellPath() {
    if (!System.getProperty("os.name").lowercase().contains("mac")) return
    try {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        val process = ProcessBuilder(shell, "-l", "-c", "echo \$PATH")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (finished && process.exitValue() == 0) {
            val pathLine = output.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .lastOrNull { line -> line.split(":").any { it.startsWith("/") } }
            if (pathLine != null) {
                setEnvironmentVariable("PATH", pathLine)
                return
            }
        }
    } catch (_: Exception) {
    }
    val current = System.getenv("PATH") ?: "/usr/bin:/bin:/usr/sbin:/sbin"
    val extras = listOf("/usr/local/bin", "/opt/homebrew/bin", "/opt/homebrew/sbin")
    val augmented = (current.split(":") + extras).filter { it.isNotBlank() }.distinct().joinToString(":")
    try {
        setEnvironmentVariable("PATH", augmented)
    } catch (_: Exception) {
    }
}

@Suppress("UNCHECKED_CAST")
private fun setEnvironmentVariable(key: String, value: String) {
    val env = System.getenv()
    val field = env.javaClass.getDeclaredField("m")
    field.isAccessible = true
    (field.get(env) as MutableMap<String, String>)[key] = value
}

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
    inheritShellPath()
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
