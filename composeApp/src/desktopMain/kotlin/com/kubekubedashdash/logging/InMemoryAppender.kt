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
        val formattedMessage =
            encoder?.let {
                String(it.encode(event)).trimEnd()
            } ?: event.formattedMessage

        val entry = AppLogEntry(
            timestamp = event.timeStamp,
            level = event.level.toString(),
            loggerName = event.loggerName,
            message = event.formattedMessage,
            formattedMessage = formattedMessage,
            threadName = event.threadName,
            throwable = event.throwableProxy?.message,
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
