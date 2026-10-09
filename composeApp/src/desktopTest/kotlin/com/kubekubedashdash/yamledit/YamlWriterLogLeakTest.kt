package com.kubekubedashdash.yamledit

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kubekubedashdash.util.SecretYamlMasking
import io.fabric8.kubernetes.api.model.StatusBuilder
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D6: the write engine never logs YAML, a payload, a response body or an API error message. The
 * server's 422 here quotes a secret value (as a real admission webhook can); the test drives every
 * failure path and checks that no logged event, message or throwable text carries the value or its
 * base64 form. The appender is started and proven to record BEFORE anything is asserted about its
 * silence: an unstarted appender records nothing and would pass vacuously. Loopback only.
 */
class YamlWriterLogLeakTest {

    private val context = "cluster-a"
    private val sentinel = "s3cr3t-sentinel-value-7f3a"
    private val sentinelBase64 = Base64.getEncoder().encodeToString(sentinel.toByteArray())

    private lateinit var mock: WriterMock
    private lateinit var writer: YamlWriter
    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeTest
    fun setUp() {
        // First: an appender that is not started silently drops everything.
        appender.start()
        root.addAppender(appender)
        mock = WriterMock(context)
        writer = YamlWriter(mock.manager)
    }

    @AfterTest
    fun tearDown() {
        root.detachAppender(appender)
        appender.stop()
        mock.stop("YamlWriterLogLeakTest")
    }

    private val path = "/api/v1/namespaces/$TEST_NAMESPACE/configmaps/demo-cm"

    private fun body(): LinkedHashMap<String, Any?> = configMapManifest().also {
        it.sub("metadata")["resourceVersion"] = "7"
        it.sub("data")["a"] = sentinelBase64
    }

    private fun rejection() = StatusBuilder().withCode(422).withStatus("Failure").withReason("Invalid").withMessage("admission webhook denied the request: value $sentinel / $sentinelBase64 is not allowed").build()

    @Test
    fun `no logged event carries the payload, the server's message or the exception text`() {
        // Positive control: the appender sees a YamlWriter-logger event, so silence below is meaningful.
        LoggerFactory.getLogger(YamlWriter::class.java).info("probe-event")
        assertTrue(appender.list.any { it.formattedMessage == "probe-event" }, "the appender must record before the test can pass by recording nothing")

        mock.seedConfigMap("demo-cm", mapOf("a" to sentinelBase64))
        val live = writer.fetch(configMapTarget(), context)
        writer.replace(configMapTarget(), context, EditProjection.body(EditProjection.visible(live.json), live.json))

        mock.server.expect().put().withPath("$path?fieldManager=kubekubedashdash&dryRun=All").andReturn(422, rejection()).once()
        mock.server.expect().put().withPath("$path?fieldManager=kubekubedashdash").andReturn(422, rejection()).once()
        mock.server.expect().get().withPath("/apis/example.com/v1/namespaces/$TEST_NAMESPACE/widgets/w1").andReturn(500, rejection()).once()
        val thrown = mutableListOf<YamlWriteException>()
        thrown += assertFailsWith<YamlWriteException> { writer.dryRunReplace(configMapTarget(), context, body()) }
        thrown += assertFailsWith<YamlWriteException> { writer.replace(configMapTarget(), context, body()) }
        thrown += assertFailsWith<YamlWriteException> { writer.apply(applyDocument(widgetTarget(), configMapManifest()), context) }
        // A refusal before any request: the mask guard, whose exception message must not be logged either.
        val masked = body().also { it.sub("data")["a"] = SecretYamlMasking.PLACEHOLDER }
        thrown += assertFailsWith<YamlWriteException> { writer.replace(configMapTarget(), context, masked) }
        thrown += assertFailsWith<YamlWriteException> { writer.fetch(configMapTarget("ghost"), context) }

        assertTrue(thrown.take(2).all { sentinel in it.message }, "the sentinel did reach the exceptions the caller shows to the person")
        val writerEvents = appender.list.filter { it.loggerName == YamlWriter::class.java.name }
        assertTrue(writerEvents.size > 1, "besides the probe, the writer logged its write and the failures: ${writerEvents.map { it.formattedMessage }}")
        assertTrue(writerEvents.any { it.formattedMessage.startsWith("Replace failed ") && "code=422" in it.formattedMessage && "kind=Invalid" in it.formattedMessage })
        for (event in appender.list) {
            val texts = listOfNotNull(event.formattedMessage, event.message, event.throwableProxy?.message, event.argumentArray?.joinToString { it.toString() })
            for (text in texts) {
                assertFalse(sentinel in text, "a logged event carries the secret: $text")
                assertFalse(sentinelBase64 in text, "a logged event carries the base64 secret: $text")
            }
            assertTrue(event.throwableProxy == null || event.loggerName != YamlWriter::class.java.name, "the writer never passes an exception to the logger")
        }
    }
}
