package com.kubekubedashdash.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

/**
 * Lets only the app's own loggers through (`com.kubekubedashdash` and below). Third-party
 * messages carry API paths, namespaces and stack traces, and app.log is attached to bug reports;
 * the app's own failures are logged by shape already (see `describeFailureShape`), so a library
 * line adds nothing the file may keep.
 */
class AppLoggersOnlyFilter : Filter<ILoggingEvent>() {
    override fun decide(event: ILoggingEvent): FilterReply {
        val name = event.loggerName
        return if (name == APP_LOGGER || name.startsWith("$APP_LOGGER.")) FilterReply.NEUTRAL else FilterReply.DENY
    }

    private companion object {
        const val APP_LOGGER = "com.kubekubedashdash"
    }
}
