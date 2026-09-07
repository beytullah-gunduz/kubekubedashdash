package com.kubekubedashdash.data.repository

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kubekubedashdash.data.datastore.PreferenceStorageHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the "PreferenceStorage" logger receives from a failed save: a storage
 * failure (an IOException) by class name only, because its message usually
 * repeats the file's path; anything else with its stack trace, which the
 * JVM uncaught handler used to print before the scope handler took over.
 */
class PreferenceStorageLoggingTest {

    private val logger = LoggerFactory.getLogger("PreferenceStorage") as Logger
    private val events = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeTest
    fun attach() {
        logger.addAppender(events)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(events)
        events.stop()
    }

    @Test
    fun `an IOException is logged by class name, without its message or trace`() = runBlocking<Unit> {
        failingWrite(IOException("would repeat the file's path"))

        val event = events.list.single()
        assertEquals("test: saving preferences failed (IOException); the change may be lost", event.formattedMessage)
        assertNull(event.throwableProxy, "no trace, so the path never reaches the log")
        assertFalse(event.formattedMessage.contains("file's path"))
    }

    @Test
    fun `anything else keeps its stack trace`() = runBlocking<Unit> {
        failingWrite(IllegalStateException("a bug in an edit body"))

        val event = events.list.single()
        assertEquals("test: saving preferences failed (IllegalStateException); the change may be lost", event.formattedMessage)
        assertEquals("java.lang.IllegalStateException", assertNotNull(event.throwableProxy, "the trace is kept").className)
    }

    private suspend fun failingWrite(cause: Exception) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + preferenceWriteFailureHandler("test", PreferenceStorageHealth()))
        scope.launch { throw cause }.join()
    }
}
