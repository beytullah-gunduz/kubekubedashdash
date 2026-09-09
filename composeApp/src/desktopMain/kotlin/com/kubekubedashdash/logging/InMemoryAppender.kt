package com.kubekubedashdash.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.encoder.Encoder
import java.util.concurrent.ConcurrentLinkedDeque

class InMemoryAppender : AppenderBase<ILoggingEvent>() {
    var maxEntries: Int = 500
    var encoder: Encoder<ILoggingEvent>? = null

    private val entries = ConcurrentLinkedDeque<AppLogEntry>()

    override fun start() {
        encoder?.start()
        super.start()
    }

    override fun stop() {
        super.stop()
        encoder?.stop()
    }

    override fun append(event: ILoggingEvent) {
        // The pattern folds the rendered line (%fold in logback.xml); the raw
        // fields kept beside it are folded here too, so no reader of an
        // AppLogEntry sees a home path whatever the configured pattern (F15).
        val formattedMessage = HomePathFolding.fold(encoder?.let { String(it.encode(event)).trimEnd() } ?: event.formattedMessage)

        val entry = AppLogEntry(
            timestamp = event.timeStamp,
            level = event.level.toString(),
            loggerName = event.loggerName,
            message = HomePathFolding.fold(event.formattedMessage),
            formattedMessage = formattedMessage,
            threadName = event.threadName,
            throwable = event.throwableProxy?.message?.let(HomePathFolding::fold),
        )

        entries.addLast(entry)
        while (entries.size > maxEntries) {
            entries.pollFirst()
        }

        AppLogStore.addEntry(entry)
    }

    companion object {
        @Volatile
        private var instance: InMemoryAppender? = null

        fun getInstance(): InMemoryAppender? = instance
    }

    init {
        instance = this
    }
}
