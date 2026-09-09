package com.kubekubedashdash.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.pattern.CompositeConverter

/** `%fold(…)` in logback.xml: renders its sub-pattern with the home directory folded to `~` (see [HomePathFolding]). */
class HomePathConverter : CompositeConverter<ILoggingEvent>() {
    override fun transform(event: ILoggingEvent, rendered: String): String = HomePathFolding.fold(rendered)
}
