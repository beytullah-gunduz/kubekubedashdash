package com.kubekubedashdash.yamledit.session

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.ui.feedback.ActionFeedback
import com.kubekubedashdash.ui.feedback.UndoAction
import com.kubekubedashdash.util.SecretYamlMasking
import com.kubekubedashdash.yamledit.DiffOp
import com.kubekubedashdash.yamledit.EditProjection
import com.kubekubedashdash.yamledit.EditTarget
import com.kubekubedashdash.yamledit.EditorYaml
import com.kubekubedashdash.yamledit.Sent
import com.kubekubedashdash.yamledit.TEST_NAMESPACE
import com.kubekubedashdash.yamledit.WriterMock
import com.kubekubedashdash.yamledit.YamlWriter
import com.kubekubedashdash.yamledit.configMapTarget
import com.kubekubedashdash.yamledit.widgetTarget
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The edit session's state machine against the fabric8 CRUD mock the demo cluster runs on, with
 * expectations for what the mock cannot do (a dry run is persisted by it, so every real-mode
 * review answers its `dryRun=All` PUT from an expectation; 422 and 403 are expectations too).
 * The session runs on `backgroundScope`; `io` and `compute` are test dispatchers on the same
 * scheduler, so a virtual-time `advanceTimeBy` + `runCurrent` drives the poll and the debounce and
 * the blocking mock calls run on the test thread. Loopback only; no real user state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YamlEditSessionTest {

    private val context = "cluster-a"
    private lateinit var mock: WriterMock

    @BeforeTest
    fun setUp() {
        mock = WriterMock(context)
    }

    @AfterTest
    fun tearDown() {
        mock.stop("YamlEditSessionTest")
    }

    private val cmPath = "/api/v1/namespaces/$TEST_NAMESPACE/configmaps/demo-cm"
    private val widgetPath = "/apis/example.com/v1/namespaces/$TEST_NAMESPACE/widgets/w1"
    private val secretPath = "/api/v1/namespaces/$TEST_NAMESPACE/secrets/demo-secret"
    private val widgetRdc = ResourceDefinitionContext.Builder().withGroup("example.com").withVersion("v1").withKind("Widget").withPlural("widgets").withNamespaced(true).build()
    private val secretTarget = EditTarget("Secret", "", "v1", "secrets", true, "demo-secret", TEST_NAMESPACE)
    private val demoWriter get() = YamlWriter(mock.manager, demoMode = { true })

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private class FeedbackEvent(val kind: String, val title: String, val detail: String?)

    private class RecordingFeedback : ActionFeedback {
        val events = mutableListOf<FeedbackEvent>()

        override fun success(title: String, detail: String?, undo: UndoAction?): Long = record("success", title, detail)

        override fun failure(title: String, detail: String?): Long = record("failure", title, detail)

        override fun warning(title: String, detail: String?): Long = record("warning", title, detail)

        override fun info(title: String, detail: String?): Long = record("info", title, detail)

        private fun record(kind: String, title: String, detail: String?): Long {
            events += FeedbackEvent(kind, title, detail)
            return events.size.toLong()
        }
    }

    /** An io dispatcher that can hold work back: after [holdAfter] dispatches, every dispatch waits for [release]. */
    private class GatedIo(scheduler: TestCoroutineScheduler) : CoroutineDispatcher() {
        private val inner = StandardTestDispatcher(scheduler)
        private val held = ArrayList<Pair<CoroutineContext, Runnable>>()
        private var allowance = Int.MAX_VALUE
        val pending: Int get() = held.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (allowance > 0) {
                allowance--
                inner.dispatch(context, block)
            } else {
                held += context to block
            }
        }

        /** Lets [passes] more dispatches through, then holds the rest. */
        fun holdAfter(passes: Int = 0) {
            allowance = passes
        }

        fun release() {
            allowance = Int.MAX_VALUE
            val waiting = ArrayList(held)
            held.clear()
            for ((context, block) in waiting) inner.dispatch(context, block)
        }
    }

    private class Opened(val session: YamlEditSession, val buffer: StringEditorBuffer, val feedback: RecordingFeedback, val closed: MutableList<Long>) {
        val reviewing: EditPhase.Reviewing get() = assertIs<EditPhase.Reviewing>(session.phase.value)

        /** Replaces [old] by [new] in the buffer, like a person editing the text. */
        fun edit(old: String, new: String) {
            val text = buffer.text()
            assertTrue(old in text, "the buffer has no '$old':\n$text")
            buffer.type(text.replace(old, new), replace = true)
        }
    }

    private fun TestScope.open(
        target: EditTarget = configMapTarget(),
        writer: YamlWriter = YamlWriter(mock.manager),
        masking: () -> Boolean = { false },
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        feedback: RecordingFeedback = RecordingFeedback(),
        start: Boolean = true,
    ): Opened {
        val buffer = StringEditorBuffer()
        val closed = mutableListOf<Long>()
        val session = YamlEditSession(
            id = 7,
            clusterSessionId = SessionId("tab-1"),
            context = context,
            target = target,
            writer = writer,
            feedback = feedback,
            masking = masking,
            buffer = buffer,
            scope = backgroundScope,
            io = io,
            compute = StandardTestDispatcher(testScheduler),
            pollIntervalMs = POLL,
            onClosed = { closed += it },
        )
        if (start) {
            session.start()
            runCurrent()
        }
        return Opened(session, buffer, feedback, closed)
    }

    /** One poll interval: runs everything due at the end of it, the tick included. */
    private fun TestScope.tick(times: Int = 1) {
        repeat(times) {
            advanceTimeBy(POLL)
            runCurrent()
        }
    }

    private fun TestScope.review(o: Opened) {
        o.session.review()
        runCurrent()
    }

    private fun failure(code: Int, message: String): Status = StatusBuilder().withCode(code).withStatus("Failure").withReason("Invalid").withMessage(message).build()

    private fun dryRunPath(path: String) = "$path?fieldManager=kubekubedashdash&dryRun=All"

    private fun replacePath(path: String) = "$path?fieldManager=kubekubedashdash"

    /** The real cluster's dry run: answered from an expectation, because the mock would persist it. */
    private fun expectDryRunPasses(path: String, kind: String = "ConfigMap") {
        mock.server.expect().put().withPath(dryRunPath(path)).andReturn(200, """{"apiVersion":"v1","kind":"$kind","metadata":{"name":"x","resourceVersion":"99"}}""").always()
    }

    /** Someone else changes the ConfigMap's `app` label: a visible change, the resourceVersion moves. */
    private fun serverSetLabel(value: String) {
        val operation = mock.seed.configMaps().inNamespace(TEST_NAMESPACE)
        val current = operation.withName("demo-cm").get()
        current.metadata.labels = (current.metadata.labels ?: emptyMap()) + ("app" to value)
        operation.resource(current).update()
    }

    /** Someone else (a controller) writes the Widget's status and generation: nothing the editor shows changes. */
    private fun serverSetWidgetStatus(phase: String, generation: Long) {
        val operation = mock.seed.genericKubernetesResources(widgetRdc).inNamespace(TEST_NAMESPACE)
        val current = operation.withName("w1").get()
        current.additionalProperties["status"] = mapOf("phase" to phase)
        current.metadata.generation = generation
        operation.resource(current).update()
    }

    /**
     * From now on the Widget reads as if a controller wrote [phase] to its status: a new resourceVersion
     * and generation (the mock keeps its own generation on a PUT, so an expectation serves it), the
     * same spec. A real CR without a status subresource bumps both on a status write.
     */
    private fun serveWidgetAs(resourceVersion: String, generation: Long, phase: String) {
        val json = YamlWriter(mock.manager).fetch(widgetTarget(), context).json

        @Suppress("UNCHECKED_CAST")
        val metadata = json["metadata"] as MutableMap<String, Any?>
        metadata["resourceVersion"] = resourceVersion
        metadata["generation"] = generation
        json["status"] = mapOf("phase" to phase)
        mock.server.expect().get().withPath(widgetPath).andReturn(200, Serialization.asJson(json)).always()
    }

    private fun seedSecret(vararg data: Pair<String, String>) {
        val secret = SecretBuilder().withNewMetadata().withName("demo-secret").withNamespace(TEST_NAMESPACE).endMetadata().withData<String, String>(mapOf(*data)).build()
        mock.seed.secrets().inNamespace(TEST_NAMESPACE).resource(secret).create()
    }

    private fun serverResourceVersion(path: String): String = ((Serialization.unmarshal(mock.manager.clientIfConnectedTo(context)!!.raw(path), Map::class.java)["metadata"]) as Map<*, *>)["resourceVersion"] as String

    @Suppress("UNCHECKED_CAST")
    private fun Sent.json(): Map<String, Any?> = Serialization.unmarshal(body, Map::class.java) as Map<String, Any?>

    private fun Map<String, Any?>.resourceVersion(): String = ((this["metadata"] as Map<*, *>)["resourceVersion"]) as String

    /** The lines a review shows as removed or added. */
    private fun EditPhase.Reviewing.changedLines(): List<String> = ops.mapNotNull {
        when (it) {
            is DiffOp.Delete -> oldText.lines()[it.oldIndex]
            is DiffOp.Insert -> newText.lines()[it.newIndex]
            is DiffOp.Equal -> null
        }
    }

    private val List<Sent>.writes get() = filter { it.method != "GET" }

    // ── Load ───────────────────────────────────────────────────────────────────

    @Test
    fun `load seeds the buffer with the visible projection and hides managedFields`() = runTest {
        mock.seedConfigMap(withManagedFields = true)
        val live = YamlWriter(mock.manager).fetch(configMapTarget(), context)
        assertTrue(live.json.containsKey("metadata") && "managedFields" in Serialization.asJson(live.json), "the fixture has managedFields, so their absence below means something")

        val o = open()

        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertEquals(EditorYaml.dump(EditProjection.visible(live.json)), o.buffer.text())
        assertEquals(o.session.base.value!!.baseText, o.buffer.text())
        assertEquals(live.resourceVersion, o.session.base.value!!.resourceVersion)
        assertFalse("managedFields" in o.buffer.text())
        assertEquals(1, o.buffer.historyResets, "seeding clears the undo history")
        assertFalse(o.session.isDirtyNow())
        assertFalse(o.session.dirty.value)
        assertNull(o.session.banner.value)
    }

    @Test
    fun `load seeds the buffer without the status`() = runTest {
        mock.seedWidget(status = mapOf("phase" to "Ready"))

        val o = open(target = widgetTarget())

        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertIs<Map<*, *>>(o.session.base.value!!.full["status"], "the base keeps the whole object")
        assertFalse(o.session.base.value!!.visible.containsKey("status"))
        assertFalse("status" in o.buffer.text(), o.buffer.text())
        assertFalse("Ready" in o.buffer.text())
    }

    @Test
    fun `a failed load is LoadFailed with the server's message and retryLoad loads again`() = runTest {
        mock.seedConfigMap()
        mock.server.expect().get().withPath(cmPath).andReturn(403, failure(403, "configmaps \"demo-cm\" is forbidden")).once()

        val o = open()

        val failed = assertIs<EditPhase.LoadFailed>(o.session.phase.value)
        assertEquals("Permission denied (403): configmaps \"demo-cm\" is forbidden", failed.message)
        assertFalse(o.session.isDirtyNow(), "nothing is loaded, so nothing can be unsaved")
        o.session.review()
        runCurrent()
        assertIs<EditPhase.LoadFailed>(o.session.phase.value, "review is for Editing only")

        o.session.retryLoad()
        runCurrent()

        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertTrue(o.buffer.text().isNotEmpty())
    }

    @Test
    fun `an object that is not there fails the load with the not-found text`() = runTest {
        val o = open()

        val failed = assertIs<EditPhase.LoadFailed>(o.session.phase.value)
        assertEquals("ConfigMap $TEST_NAMESPACE/demo-cm was not found on the cluster.", failed.message)
    }

    // ── Local checks: no request leaves ───────────────────────────────────────

    @Test
    fun `a parse error is a parseProblem with line and column and costs no request`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.buffer.type("a: 1\nb: 2\na: 3\n", replace = true)

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.map { it.toString() })
        assertEquals(EditPhase.Editing, o.session.phase.value)
        val problem = assertNotNull(o.session.parseProblem.value)
        assertEquals(3, problem.line, "the duplicate key is on line 3")
        assertTrue(problem.column >= 1)
        assertTrue(problem.message.isNotBlank())
    }

    @Test
    fun `typing invalid YAML shows the parse problem after the debounce and fixing it clears it`() = runTest {
        mock.seedConfigMap()
        val o = open()

        o.buffer.type("\n  : : [")
        assertNull(o.session.parseProblem.value, "nothing before the debounce")
        advanceTimeBy(299)
        runCurrent()
        assertNull(o.session.parseProblem.value)
        advanceTimeBy(1)
        runCurrent()
        assertNotNull(o.session.parseProblem.value)
        assertTrue(o.session.dirty.value)

        o.buffer.replaceAll(o.session.base.value!!.baseText, resetHistory = false)
        advanceTimeBy(300)
        runCurrent()
        assertNull(o.session.parseProblem.value)
        assertFalse(o.session.dirty.value, "back to the seed text")
    }

    @Test
    fun `an identity change is a problem and costs no request`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.edit("name: demo-cm", "name: other-cm")

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.map { it.toString() })
        assertEquals(EditPhase.Editing, o.session.phase.value)
        val problem = o.session.problems.value.single()
        assertTrue(problem.message.startsWith("metadata.name must stay \"demo-cm\""), problem.message)
    }

    @Test
    fun `a status block typed into the buffer is a problem and costs no request`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.buffer.type("status:\n  phase: Ready\n")

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.map { it.toString() })
        assertTrue(o.session.problems.value.single().message.startsWith("status is hidden"))
    }

    @Test
    fun `a mask token in the buffer is a problem with its line and costs no request`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.edit("app: demo", "app: ${SecretYamlMasking.PLACEHOLDER}")
        val line = o.buffer.text().lines().indexOfFirst { SecretYamlMasking.PLACEHOLDER in it } + 1

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.map { it.toString() })
        assertEquals(EditPhase.Editing, o.session.phase.value)
        val problem = o.session.problems.value.single()
        assertTrue(SecretYamlMasking.PLACEHOLDER in problem.message)
        assertEquals(line, problem.line)
    }

    @Test
    fun `a recursive alias in the buffer is a parse problem and costs no request`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.edit("app: demo", "app: &l {self: *l}")

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.map { it.toString() })
        assertEquals(EditPhase.Editing, o.session.phase.value)
        val problem = assertNotNull(o.session.parseProblem.value)
        assertEquals("Recursive YAML aliases are not supported.", problem.message)
        assertEquals(1, problem.line)
        assertTrue(o.session.problems.value.isEmpty())
    }

    @Test
    fun `an Error thrown inside the review is shown as a problem, not swallowed`() = runTest {
        mock.seedConfigMap()
        val inner = StringEditorBuffer()
        val buffer = ErroringBuffer(inner)
        val session = YamlEditSession(
            id = 7,
            clusterSessionId = SessionId("tab-1"),
            context = context,
            target = configMapTarget(),
            writer = YamlWriter(mock.manager),
            feedback = RecordingFeedback(),
            masking = { false },
            buffer = buffer,
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            compute = StandardTestDispatcher(testScheduler),
            pollIntervalMs = POLL,
            onClosed = {},
        )
        session.start()
        runCurrent()
        assertEquals(EditPhase.Editing, session.phase.value)

        buffer.failing = true
        session.review()
        runCurrent()
        buffer.failing = false

        assertEquals("Unexpected error: StackOverflowError", session.problems.value.single().message)
        assertEquals(EditPhase.Editing, session.phase.value)
    }

    // ── Review and dry run ─────────────────────────────────────────────────────

    @Test
    fun `an edit is reviewed with a diff, the exact body and a passing dry run`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val o = open()
        o.edit("app: demo", "app: edited")

        val requests = mock.recorded { review(o) }

        val reviewing = o.reviewing
        assertEquals(DryRunState.Passed(simulatedLocally = false), reviewing.dryRun)
        assertEquals(o.buffer.text(), reviewing.newText)
        assertEquals(listOf("    app: demo", "    app: edited"), reviewing.changedLines().sorted())
        assertEquals(listOf("GET", "PUT"), requests.map { it.method }, "the re-GET, then the dry run")
        val dryRun = requests.last()
        assertEquals(dryRunPath(cmPath), dryRun.path)
        assertEquals(Serialization.asJson(reviewing.body), dryRun.body, "the dry run sends exactly the body Apply will send")
        assertEquals(o.session.base.value!!.resourceVersion, reviewing.body!!.resourceVersion())
        assertTrue(o.session.problems.value.isEmpty())
    }

    @Test
    fun `an unchanged buffer is NoChanges and blocks apply`() = runTest {
        mock.seedConfigMap()
        val o = open()

        val requests = mock.recorded {
            review(o)
            o.session.requestApply()
            o.session.confirmApply()
            runCurrent()
        }

        val reviewing = o.reviewing
        assertEquals(DryRunState.NoChanges, reviewing.dryRun)
        assertNull(reviewing.body)
        assertEquals(ApplyState.Idle, o.session.apply.value)
        assertTrue(requests.writes.isEmpty(), "no dry run, no PUT: $requests")
    }

    @Test
    fun `an edit that only adds a comment is NoChanges too`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.buffer.type("# a note to self\n")

        review(o)

        assertEquals(DryRunState.NoChanges, o.reviewing.dryRun)
        o.session.requestApply()
        assertEquals(ApplyState.Idle, o.session.apply.value)
        assertEquals(listOf("# a note to self"), o.reviewing.changedLines().filter { it.isNotBlank() })
    }

    @Test
    fun `a failed dry run blocks apply and sends nothing`() = runTest {
        mock.seedConfigMap()
        mock.server.expect().put().withPath(dryRunPath(cmPath)).andReturn(422, failure(422, "ConfigMap \"demo-cm\" is invalid: data: Too long")).once()
        val o = open()
        o.edit("app: demo", "app: edited")
        review(o)

        val failed = assertIs<DryRunState.Failed>(o.reviewing.dryRun)
        assertEquals("ConfigMap \"demo-cm\" is invalid: data: Too long", failed.message)
        val requests = mock.recorded {
            o.session.requestApply()
            assertEquals(ApplyState.Idle, o.session.apply.value)
            o.session.confirmApply()
            runCurrent()
        }

        assertEquals(ApplyState.Idle, o.session.apply.value)
        assertEquals(emptyList(), requests.map { it.toString() }, "the request count did not move")
        assertTrue(o.closed.isEmpty())
    }

    @Test
    fun `a dry-run conflict fails the dry run and raises the Conflict banner with the latest object`() = runTest {
        mock.seedConfigMap()
        mock.server.expect().put().withPath(dryRunPath(cmPath)).andReturn(409, failure(409, "the object has been modified")).once()
        val o = open()
        o.edit("app: demo", "app: edited")

        review(o)

        assertEquals(DryRunState.Failed("This ConfigMap changed on the server; use Compare with latest."), o.reviewing.dryRun)
        val banner = assertIs<EditBanner.Conflict>(o.session.banner.value)
        assertNotNull(banner.latest)
        o.session.requestApply()
        assertEquals(ApplyState.Idle, o.session.apply.value)
    }

    @Test
    fun `back to the editor leaves the review and the buffer alone`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val o = open()
        o.edit("app: demo", "app: edited")
        val typed = o.buffer.text()
        review(o)
        o.session.requestApply()
        assertEquals(ApplyState.Confirming, o.session.apply.value)

        o.session.backToEditor()

        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertEquals(ApplyState.Idle, o.session.apply.value)
        assertEquals(typed, o.buffer.text())
    }

    @Test
    fun `demo mode passes the dry run locally and sends no write`() = runTest {
        mock.seedConfigMap()
        val o = open(writer = demoWriter)
        o.edit("app: demo", "app: edited")

        val requests = mock.recorded { review(o) }

        assertEquals(DryRunState.Passed(simulatedLocally = true), o.reviewing.dryRun)
        assertEquals(listOf("GET"), requests.map { it.method }, "only the re-GET: a dry run would be persisted by the mock")
        assertEquals("demo", mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("demo-cm").get().metadata.labels["app"], "nothing was written")
    }

    // ── Apply ──────────────────────────────────────────────────────────────────

    @Test
    fun `apply success reports the toast, sends the reviewed body and closes the session`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val o = open()
        o.edit("app: demo", "app: edited")
        val review = mock.recorded { review(o) }
        val body = Serialization.asJson(o.reviewing.body)
        assertEquals(body, review.last().body)

        o.session.requestApply()
        assertEquals(ApplyState.Confirming, o.session.apply.value)
        val requests = mock.recorded {
            o.session.confirmApply()
            assertEquals(ApplyState.InFlight, o.session.apply.value)
            runCurrent()
        }

        val put = requests.single()
        assertEquals("PUT", put.method)
        assertEquals(replacePath(cmPath), put.path)
        assertEquals(body, put.body, "exactly the Reviewing body, which is the body that was dry-run")
        assertEquals("edited", mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("demo-cm").get().metadata.labels["app"])
        val toast = o.feedback.events.single()
        assertEquals("success", toast.kind)
        assertEquals("Applied changes to ConfigMap \"example-ns/demo-cm\"", toast.title)
        assertNull(toast.detail)
        assertEquals(listOf(7L), o.closed)
        assertTrue(backgroundScope.isActive, "closing the session never cancels the injected scope")
    }

    @Test
    fun `apply works on the demo cluster through the same replace`() = runTest {
        mock.seedConfigMap()
        val o = open(writer = demoWriter)
        o.edit("app: demo", "app: edited")
        review(o)

        o.session.requestApply()
        val requests = mock.recorded {
            o.session.confirmApply()
            runCurrent()
        }

        assertEquals(listOf("PUT"), requests.map { it.method })
        assertEquals(replacePath(cmPath), requests.single().path)
        assertEquals("edited", mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("demo-cm").get().metadata.labels["app"])
        assertEquals(listOf(7L), o.closed)
    }

    @Test
    fun `a 409 on apply keeps the buffer, raises Conflict and goes back to Editing`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val o = open()
        o.edit("app: demo", "app: edited")
        review(o)
        o.session.requestApply()
        val typed = o.buffer.text()
        serverSetLabel("someone-else") // the object moves between the dry run and the confirm

        o.session.confirmApply()
        runCurrent()

        assertEquals(typed, o.buffer.text(), "the buffer is untouched")
        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertEquals(ApplyState.Idle, o.session.apply.value)
        val banner = assertIs<EditBanner.Conflict>(o.session.banner.value)
        assertEquals("someone-else", (banner.latest!!.json["metadata"] as Map<*, *>).let { (it["labels"] as Map<*, *>)["app"] })
        assertTrue(o.closed.isEmpty())
        assertTrue(o.feedback.events.isEmpty(), "no success toast")
        assertEquals("someone-else", mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("demo-cm").get().metadata.labels["app"], "the stale write did not land")

        // Compare with latest from the Conflict banner: the buffer is reviewed against the object that won.
        o.session.compareWithLatest()
        runCurrent()

        assertNull(o.session.banner.value)
        assertEquals(banner.latest.resourceVersion, o.session.base.value!!.resourceVersion)
        val reviewing = o.reviewing
        assertEquals(DryRunState.Passed(simulatedLocally = false), reviewing.dryRun)
        assertEquals(banner.latest.resourceVersion, reviewing.body!!.resourceVersion())
        assertTrue(reviewing.changedLines().none { "resourceVersion" in it })
        assertTrue(reviewing.changedLines().any { "someone-else" in it })
        assertTrue(reviewing.changedLines().any { "app: edited" in it })
    }

    @Test
    fun `any other apply failure is shown with the buffer and the review untouched`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        mock.server.expect().put().withPath(replacePath(cmPath)).andReturn(403, failure(403, "configmaps \"demo-cm\" is forbidden")).once()
        val o = open()
        o.edit("app: demo", "app: edited")
        review(o)
        val typed = o.buffer.text()
        o.session.requestApply()

        o.session.confirmApply()
        runCurrent()

        assertEquals(ApplyState.Failed("Permission denied (403): configmaps \"demo-cm\" is forbidden"), o.session.apply.value)
        assertEquals(typed, o.buffer.text())
        assertIs<EditPhase.Reviewing>(o.session.phase.value)
        assertTrue(o.closed.isEmpty())
        assertNull(o.session.banner.value)

        o.session.cancelApply()
        assertEquals(ApplyState.Idle, o.session.apply.value)
        o.session.requestApply()
        assertEquals(ApplyState.Confirming, o.session.apply.value, "a retry is possible, and is a fresh confirmation")
    }

    @Test
    fun `confirmApply without a confirmation does nothing`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val o = open()
        o.edit("app: demo", "app: edited")
        review(o)

        val requests = mock.recorded {
            o.session.confirmApply()
            runCurrent()
        }

        assertEquals(emptyList(), requests.map { it.toString() })
        assertEquals(ApplyState.Idle, o.session.apply.value)
    }

    @Test
    fun `requestClose during an apply in flight is ignored`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val gate = GatedIo(testScheduler)
        val o = open(io = gate)
        o.edit("app: demo", "app: edited")
        review(o)
        o.session.requestApply()
        gate.holdAfter()
        o.session.confirmApply()
        runCurrent()
        assertEquals(ApplyState.InFlight, o.session.apply.value)
        assertEquals(1, gate.pending, "the PUT is on its way")

        o.session.requestClose()
        tick() // the poll skips its tick while an apply is under way

        assertFalse(o.session.closePrompt.value)
        assertTrue(o.closed.isEmpty())
        assertEquals(1, gate.pending, "the tick did not read")
        gate.release()
        runCurrent()
        assertEquals(listOf(7L), o.closed, "the apply completes and closes the session itself")
    }

    @Test
    fun `confirmApply refuses text that changed after the review and sends nothing`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val o = open()
        o.edit("app: demo", "app: edited")
        review(o)
        o.session.requestApply()
        assertEquals(ApplyState.Confirming, o.session.apply.value)
        o.edit("a: '1'", "a: sneaked-in") // after the dry run, before the confirm

        val requests = mock.recorded {
            o.session.confirmApply()
            runCurrent()
        }

        assertEquals(emptyList(), requests.map { it.toString() }, "what was dry-run is not what the buffer says")
        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertEquals(ApplyState.Idle, o.session.apply.value)
        assertEquals("The text changed while it was being checked; review again.", o.session.problems.value.single().message)
        assertTrue(o.buffer.text().contains("sneaked-in"), "the buffer is untouched")
        assertTrue(o.closed.isEmpty(), "the session stays open")
        assertTrue(o.feedback.events.isEmpty())
        assertEquals("demo", mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("demo-cm").get().metadata.labels["app"], "nothing was written")
    }

    @Test
    fun `closing under an apply in flight warns that the change may have landed`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val gate = GatedIo(testScheduler)
        val o = open(io = gate)
        o.edit("app: demo", "app: edited")
        review(o)
        o.session.requestApply()
        gate.holdAfter()
        o.session.confirmApply()
        runCurrent()
        assertEquals(ApplyState.InFlight, o.session.apply.value)
        assertEquals(1, gate.pending, "the PUT is on its way")

        o.session.close() // what a guard's Discard does: requestClose would have been ignored

        val toast = o.feedback.events.single()
        assertEquals("warning", toast.kind)
        assertEquals("Closed while applying to ConfigMap \"$TEST_NAMESPACE/demo-cm\"", toast.title)
        assertEquals("The change may or may not have been applied. Check the object before editing it again.", toast.detail)
        assertEquals(listOf(7L), o.closed)
        gate.release()
        runCurrent()
        o.session.close()
        assertEquals(1, o.feedback.events.size, "the cancelled apply adds no toast and a second close none either")
        assertEquals(listOf(7L), o.closed)
    }

    @Test
    fun `closing with no apply in flight raises no toast`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val reviewed = open()
        reviewed.edit("app: demo", "app: edited")
        review(reviewed)
        reviewed.session.requestApply()
        assertEquals(ApplyState.Confirming, reviewed.session.apply.value)

        reviewed.session.close()

        assertEquals(listOf(7L), reviewed.closed)
        assertTrue(reviewed.feedback.events.isEmpty(), "a confirmation that was never answered is not an apply")
        val clean = open()
        clean.session.close()
        assertTrue(clean.feedback.events.isEmpty())
    }

    // ── Server changes while the window is open ───────────────────────────────

    @Test
    fun `a visible server change raises ServerChanged and leaves base and buffer alone`() = runTest {
        mock.seedConfigMap()
        val o = open()
        val before = o.session.base.value!!
        val typed = o.buffer.text()

        tick()
        assertNull(o.session.banner.value, "an unchanged server raises nothing")
        serverSetLabel("someone-else")
        tick()

        val banner = assertIs<EditBanner.ServerChanged>(o.session.banner.value)
        assertEquals("someone-else", ((banner.latest.json["metadata"] as Map<*, *>)["labels"] as Map<*, *>)["app"])
        assertEquals(before, o.session.base.value)
        assertEquals(typed, o.buffer.text())
        assertEquals(EditPhase.Editing, o.session.phase.value)
    }

    @Test
    fun `a status-only change is adopted silently even though the resourceVersion and generation moved`() = runTest {
        mock.seedWidget(spec = mapOf("size" to 1), status = mapOf("phase" to "Ready"))
        expectDryRunPasses(widgetPath, kind = "Widget")
        val o = open(target = widgetTarget())
        val before = o.session.base.value!!
        val seeded = o.buffer.text()

        val oldGeneration = (before.full["metadata"] as Map<*, *>)["generation"]
        serveWidgetAs(resourceVersion = "50", generation = 9, phase = "Degraded")
        tick()

        val after = o.session.base.value!!
        assertNull(o.session.banner.value, "nothing the editor shows changed")
        assertNotEquals(before.resourceVersion, after.resourceVersion, "the base moved to the new resourceVersion")
        assertEquals("50", after.resourceVersion)
        assertEquals(mapOf("phase" to "Degraded"), after.full["status"])
        assertNotEquals(9L, (oldGeneration as? Number)?.toLong(), "positive control: the generation really differs")
        assertEquals(9L, ((after.full["metadata"] as Map<*, *>)["generation"] as Number).toLong(), "the generation moved too")
        assertEquals(seeded, o.buffer.text(), "the buffer still shows the old resourceVersion line")
        assertFalse(o.session.isDirtyNow(), "an untouched buffer stays clean")
        assertTrue(o.closed.isEmpty())

        // A review of an edit pins the NEW resourceVersion and carries the CURRENT status, whatever the buffer says.
        o.edit("size: 1", "size: 5")
        val requests = mock.recorded { review(o) }

        val body = o.reviewing.body!!
        assertEquals(after.resourceVersion, body.resourceVersion())
        assertEquals(mapOf("phase" to "Degraded"), body["status"])
        val dryRun = requests.single { it.method == "PUT" }
        assertEquals(dryRunPath(widgetPath), dryRun.path)
        assertEquals(after.resourceVersion, dryRun.json().resourceVersion(), "the dry-run PUT carries the new resourceVersion")
        assertEquals(mapOf("phase" to "Degraded"), dryRun.json()["status"])
        assertTrue(o.buffer.text().contains(Regex("resourceVersion: ['\"]?${before.resourceVersion}['\"]?")), "while the buffer still shows the old one")
        assertEquals(listOf("  size: 1", "  size: 5").sorted(), o.reviewing.changedLines().sorted(), "the diff shows the edit and no resourceVersion or generation line")
    }

    @Test
    fun `a status-only change while Reviewing re-derives the body and re-runs the dry run`() = runTest {
        mock.seedWidget(spec = mapOf("size" to 1), status = mapOf("phase" to "Ready"))
        expectDryRunPasses(widgetPath, kind = "Widget")
        val gate = GatedIo(testScheduler)
        val o = open(target = widgetTarget(), io = gate)
        o.edit("size: 1", "size: 5")
        val phases = mutableListOf<EditPhase>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { o.session.phase.collect { phases += it } }
        review(o)
        val first = o.reviewing
        assertEquals(DryRunState.Passed(simulatedLocally = false), first.dryRun)
        val oldVersion = first.body!!.resourceVersion()
        mock.drain()

        serverSetWidgetStatus("Degraded", generation = 2)
        gate.holdAfter(1)
        tick() // the poll's GET passes and the base is adopted; the re-run dry run is held at the gate
        runCurrent()

        val rerunning = o.reviewing
        assertEquals(DryRunState.Running, rerunning.dryRun)
        assertEquals(first.ops, rerunning.ops)
        assertEquals(first.oldText, rerunning.oldText)
        assertEquals(first.newText, rerunning.newText)
        val newVersion = o.session.base.value!!.resourceVersion
        assertNotEquals(oldVersion, newVersion)
        assertEquals(newVersion, rerunning.body!!.resourceVersion(), "the body is re-derived on the new base")
        assertEquals(mapOf("phase" to "Degraded"), rerunning.body["status"])
        assertNull(o.session.banner.value)
        o.session.requestApply()
        assertEquals(ApplyState.Idle, o.session.apply.value, "Apply stays disabled until the re-run passes")

        gate.release()
        runCurrent()

        assertEquals(DryRunState.Passed(simulatedLocally = false), o.reviewing.dryRun)
        val dryRuns = mock.drain().filter { it.method == "PUT" && "dryRun=All" in it.path }
        assertEquals(1, dryRuns.size, "a second dry run, for the new body: $dryRuns")
        assertEquals(newVersion, dryRuns.single().json().resourceVersion())
        assertEquals(
            listOf<Class<*>>(DryRunState.Running::class.java, DryRunState.Passed::class.java, DryRunState.Running::class.java, DryRunState.Passed::class.java),
            phases.filterIsInstance<EditPhase.Reviewing>().map { it.dryRun::class.java },
        )
        o.session.requestApply()
        assertEquals(ApplyState.Confirming, o.session.apply.value)
    }

    @Test
    fun `on the demo cluster a status-only change while Reviewing passes locally again`() = runTest {
        mock.seedWidget(spec = mapOf("size" to 1), status = mapOf("phase" to "Ready"))
        val o = open(target = widgetTarget(), writer = demoWriter)
        o.edit("size: 1", "size: 5")
        val phases = mutableListOf<EditPhase>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { o.session.phase.collect { phases += it } }
        review(o)
        assertEquals(DryRunState.Passed(simulatedLocally = true), o.reviewing.dryRun)
        val oldVersion = o.reviewing.body!!.resourceVersion()

        serverSetWidgetStatus("Degraded", generation = 2)
        val requests = mock.recorded { tick() }

        assertEquals(DryRunState.Passed(simulatedLocally = true), o.reviewing.dryRun)
        assertNotEquals(oldVersion, o.reviewing.body!!.resourceVersion())
        assertEquals(listOf("GET"), requests.map { it.method }, "the poll reads; the dry run sends nothing")
        assertEquals(
            listOf<Class<*>>(DryRunState.Running::class.java, DryRunState.Passed::class.java, DryRunState.Running::class.java, DryRunState.Passed::class.java),
            phases.filterIsInstance<EditPhase.Reviewing>().map { it.dryRun::class.java },
        )
    }

    @Test
    fun `a visible change while Reviewing raises the banner and blocks apply`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val o = open()
        o.edit("app: demo", "app: edited")
        review(o)
        assertEquals(DryRunState.Passed(simulatedLocally = false), o.reviewing.dryRun)

        serverSetLabel("someone-else")
        tick()

        assertIs<EditBanner.ServerChanged>(o.session.banner.value)
        o.session.requestApply()
        assertEquals(ApplyState.Idle, o.session.apply.value)
    }

    @Test
    fun `a deletion found by the poll raises Deleted and review is disabled`() = runTest {
        mock.seedConfigMap()
        val o = open()
        mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("demo-cm").delete()

        tick()

        assertEquals(EditBanner.Deleted, o.session.banner.value)
        o.edit("app: demo", "app: edited")
        val requests = mock.recorded { review(o) }
        assertEquals(emptyList(), requests.map { it.toString() })
        assertEquals(EditPhase.Editing, o.session.phase.value)
    }

    @Test
    fun `a deletion found by the review raises Deleted and stays in Editing`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.edit("app: demo", "app: edited")
        mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("demo-cm").delete()

        val requests = mock.recorded { review(o) }

        assertEquals(EditBanner.Deleted, o.session.banner.value)
        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertTrue(requests.writes.isEmpty())
    }

    @Test
    fun `a review finding a visible change raises ServerChanged and does not review`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.edit("app: demo", "app: edited")
        serverSetLabel("someone-else")

        val requests = mock.recorded { review(o) }

        assertIs<EditBanner.ServerChanged>(o.session.banner.value)
        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertTrue(requests.writes.isEmpty())
    }

    @Test
    fun `an unreachable cluster during the review is a problem, not a banner`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.edit("app: demo", "app: edited")
        mock.server.expect().get().withPath(cmPath).andReturn(403, failure(403, "configmaps \"demo-cm\" is forbidden")).once()

        review(o)

        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertNull(o.session.banner.value)
        assertEquals("Couldn't reach the cluster: Permission denied (403): configmaps \"demo-cm\" is forbidden", o.session.problems.value.single().message)
    }

    @Test
    fun `compareWithLatest re-bases on the latest object and reviews, with no resourceVersion line in the diff`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val o = open()
        val seedVersion = o.session.base.value!!.resourceVersion
        o.edit("a: '1'", "a: '2'")
        serverSetLabel("someone-else")
        tick()
        val latest = assertIs<EditBanner.ServerChanged>(o.session.banner.value).latest

        o.session.compareWithLatest()
        runCurrent()

        assertNull(o.session.banner.value)
        assertEquals(latest.resourceVersion, o.session.base.value!!.resourceVersion)
        assertNotEquals(seedVersion, latest.resourceVersion)
        val reviewing = o.reviewing
        assertEquals(DryRunState.Passed(simulatedLocally = false), reviewing.dryRun)
        assertEquals(o.buffer.text(), reviewing.newText, "the buffer is kept")
        val changed = reviewing.changedLines()
        assertTrue(changed.any { "someone-else" in it }, "the other writer's label is on the old side: $changed")
        assertTrue(changed.any { "app: demo" in it }, "the buffer's old label is on the new side: $changed")
        assertTrue(changed.any { "a: '2'" in it })
        assertTrue(changed.none { "resourceVersion" in it }, "no resourceVersion line in the diff: $changed")
        assertTrue(reviewing.oldText.contains(Regex("resourceVersion: ['\"]?$seedVersion['\"]?")), "the old side carries the buffer's resourceVersion, not the latest")
        assertEquals(latest.resourceVersion, reviewing.body!!.resourceVersion(), "the body pins the latest")
    }

    @Test
    fun `discardEdits from the banner replaces the buffer with the latest text and clears the undo history`() = runTest {
        mock.seedConfigMap()
        val o = open()
        o.edit("a: '1'", "a: '2'")
        serverSetLabel("someone-else")
        tick()
        val latest = assertIs<EditBanner.ServerChanged>(o.session.banner.value).latest
        assertTrue(o.session.isDirtyNow())
        val resets = o.buffer.historyResets

        o.session.discardEdits()
        runCurrent()

        assertNull(o.session.banner.value)
        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertEquals(latest.resourceVersion, o.session.base.value!!.resourceVersion)
        assertEquals(o.session.base.value!!.baseText, o.buffer.text())
        assertTrue("someone-else" in o.buffer.text())
        assertEquals(resets + 1, o.buffer.historyResets)
        assertFalse(o.session.isDirtyNow())
        assertFalse(o.session.dirty.value)
        assertNull(o.session.parseProblem.value)
        assertTrue(o.session.problems.value.isEmpty())

        // The new seed is the baseline: an unedited review is a no-op against it.
        review(o)
        assertEquals(DryRunState.NoChanges, o.reviewing.dryRun)
    }

    @Test
    fun `discardEdits without a banner reads the server and resets the buffer`() = runTest {
        mock.seedConfigMap()
        val o = open()
        val seeded = o.buffer.text()
        o.edit("a: '1'", "a: '2'")
        o.buffer.type("\n: : [")
        advanceTimeBy(300)
        runCurrent()
        assertNotNull(o.session.parseProblem.value)

        val requests = mock.recorded {
            o.session.discardEdits()
            runCurrent()
        }

        assertEquals(listOf("GET"), requests.map { it.method })
        assertEquals(seeded, o.buffer.text())
        assertNull(o.session.parseProblem.value)
        assertFalse(o.session.isDirtyNow())
    }

    @Test
    fun `a buffer resourceVersion that is neither the seed's nor the base's is refused, a deleted line is fine`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val o = open()
        val version = o.session.base.value!!.resourceVersion
        val text = o.buffer.text()
        assertTrue(Regex("resourceVersion: ['\"]?$version['\"]?").containsMatchIn(text), text)

        o.buffer.type(text.replace(Regex("resourceVersion: ['\"]?$version['\"]?"), "resourceVersion: '999'"), replace = true)
        val refused = mock.recorded { review(o) }

        assertEquals(emptyList(), refused.map { it.toString() })
        assertTrue(o.session.problems.value.single().message.startsWith("metadata.resourceVersion is managed by the editor"), o.session.problems.value.toString())
        assertEquals(EditPhase.Editing, o.session.phase.value)

        val withoutLine = text.lines().filterNot { it.trim().startsWith("resourceVersion:") }.joinToString("\n").replace("app: demo", "app: edited")
        o.buffer.type(withoutLine, replace = true)
        review(o)

        assertTrue(o.session.problems.value.isEmpty())
        assertEquals(version, o.reviewing.body!!.resourceVersion(), "the body pins the base's resourceVersion")
        assertEquals(DryRunState.Passed(simulatedLocally = false), o.reviewing.dryRun)
    }

    @Test
    fun `a second review while one is running is ignored`() = runTest {
        mock.seedConfigMap()
        val gate = GatedIo(testScheduler)
        val o = open(writer = demoWriter, io = gate)
        o.edit("app: demo", "app: edited")
        gate.holdAfter()

        o.session.review()
        o.session.review()
        runCurrent()

        assertEquals(1, gate.pending, "one review, one GET")
        gate.release()
        runCurrent()
        assertIs<EditPhase.Reviewing>(o.session.phase.value)
    }

    @Test
    fun `text typed while the review is reading the server is not reviewed`() = runTest {
        mock.seedConfigMap()
        expectDryRunPasses(cmPath)
        val gate = GatedIo(testScheduler)
        val o = open(io = gate)
        o.edit("app: demo", "app: edited")
        gate.holdAfter()
        o.session.review()
        runCurrent()
        assertEquals(1, gate.pending, "the review's GET is on its way")
        o.buffer.type("# typed while the server is being asked\n")

        val requests = mock.recorded {
            gate.release()
            runCurrent()
        }

        assertEquals(EditPhase.Editing, o.session.phase.value, "a review of text that is gone is not shown")
        assertEquals("The text changed while it was being checked; review again.", o.session.problems.value.single().message)
        assertTrue(requests.writes.isEmpty(), "no dry run and no PUT: $requests")
        assertEquals(ApplyState.Idle, o.session.apply.value)

        // The review itself is not broken: the next one covers the text that is in the buffer now.
        review(o)
        assertTrue(o.session.problems.value.isEmpty())
        assertEquals(DryRunState.Passed(simulatedLocally = false), o.reviewing.dryRun)
        assertTrue("typed while the server" in o.reviewing.newText)
    }

    @Test
    fun `a poll that fails is ignored and the next tick polls again`() = runTest {
        mock.seedConfigMap()
        val o = open()
        mock.server.expect().get().withPath(cmPath).andReturn(403, failure(403, "configmaps \"demo-cm\" is forbidden")).once()

        val first = mock.recorded { tick() }
        val second = mock.recorded { tick() }

        assertEquals(listOf("GET"), first.map { it.method })
        assertEquals(listOf("GET"), second.map { it.method }, "the poll loop survived the failure")
        assertNull(o.session.banner.value)
        assertTrue(o.session.problems.value.isEmpty())
        assertEquals(EditPhase.Editing, o.session.phase.value)
    }

    @Test
    fun `the poll does not adopt while the apply is waiting for its confirmation`() = runTest {
        mock.seedWidget(spec = mapOf("size" to 1), status = mapOf("phase" to "Ready"))
        expectDryRunPasses(widgetPath, kind = "Widget")
        val o = open(target = widgetTarget())
        o.edit("size: 1", "size: 5")
        review(o)
        val body = o.reviewing.body
        o.session.requestApply()
        assertEquals(ApplyState.Confirming, o.session.apply.value)
        serverSetWidgetStatus("Degraded", generation = 2)

        val whileConfirming = mock.recorded { tick() }

        assertEquals(emptyList(), whileConfirming.map { it.toString() }, "the tick is skipped, so the confirmed body cannot change")
        assertTrue(body === o.reviewing.body)
        assertEquals(DryRunState.Passed(simulatedLocally = false), o.reviewing.dryRun)

        o.session.cancelApply()
        tick()

        assertEquals(DryRunState.Passed(simulatedLocally = false), o.reviewing.dryRun)
        assertNotEquals(body!!.resourceVersion(), o.reviewing.body!!.resourceVersion(), "once the confirmation is gone the poll adopts and re-derives")
    }

    @Test
    fun `a dry-run conflict whose re-read fails keeps the Conflict banner without a latest object`() = runTest {
        mock.seedConfigMap()
        mock.server.expect().put().withPath(dryRunPath(cmPath)).andReturn(409, failure(409, "the object has been modified")).once()
        val gate = GatedIo(testScheduler)
        val o = open(io = gate)
        o.edit("app: demo", "app: edited")
        gate.holdAfter(2) // the review's GET and the dry run pass; the re-read after the 409 waits

        o.session.review()
        runCurrent()
        assertEquals(1, gate.pending, "the re-read after the conflict is waiting")
        mock.server.expect().get().withPath(cmPath).andReturn(403, failure(403, "configmaps \"demo-cm\" is forbidden")).once()
        gate.release()
        runCurrent()

        assertEquals(DryRunState.Failed("This ConfigMap changed on the server; use Compare with latest."), o.reviewing.dryRun)
        assertEquals(EditBanner.Conflict(null), o.session.banner.value)

        // Compare needs the latest object: with none it does nothing; Discard reads the server itself.
        val noop = mock.recorded {
            o.session.compareWithLatest()
            runCurrent()
        }
        assertEquals(emptyList(), noop.map { it.toString() })
        assertEquals(EditBanner.Conflict(null), o.session.banner.value)
        o.session.discardEdits()
        runCurrent()
        assertNull(o.session.banner.value)
        assertEquals(EditPhase.Editing, o.session.phase.value)
        assertFalse(o.session.isDirtyNow())
    }

    @Test
    fun `a dry-run conflict on an object that is gone raises Deleted`() = runTest {
        mock.seedConfigMap()
        mock.server.expect().put().withPath(dryRunPath(cmPath)).andReturn(409, failure(409, "the object has been modified")).once()
        val o = open()
        o.edit("app: demo", "app: edited")
        val live = Serialization.asJson(YamlWriter(mock.manager).fetch(configMapTarget(), context).json)
        mock.server.expect().get().withPath(cmPath).andReturn(200, live).once() // the review's own GET still finds it
        mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("demo-cm").delete() // gone before the re-read

        review(o)

        assertEquals(DryRunState.Failed("This ConfigMap was deleted on the server."), o.reviewing.dryRun)
        assertEquals(EditBanner.Deleted, o.session.banner.value)
    }

    // ── Overlapping reads ──────────────────────────────────────────────────────

    @Test
    fun `a poll tick overlapping a review is serialised behind it and the base only moves forward`() = runTest {
        mock.seedWidget(spec = mapOf("size" to 1), status = mapOf("phase" to "Ready"))
        val gate = GatedIo(testScheduler)
        val o = open(target = widgetTarget(), writer = demoWriter, io = gate)
        val versions = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { o.session.base.collect { versions += it?.resourceVersion } }
        val first = o.session.base.value!!.resourceVersion
        serverSetWidgetStatus("Degraded", generation = 2)

        gate.holdAfter()
        tick() // the poll holds the read lock; its GET is held at the gate
        assertEquals(1, gate.pending)
        o.edit("size: 1", "size: 5")
        review(o)
        assertEquals(1, gate.pending, "the review's GET waits for the poll's: reads are serialised")
        assertEquals(EditPhase.Editing, o.session.phase.value)
        serverSetWidgetStatus("Healthy", generation = 3)
        val newest = serverResourceVersion(widgetPath)
        gate.release()
        runCurrent()

        assertEquals(newest, o.session.base.value!!.resourceVersion, "the newest base won")
        assertEquals<List<String?>>(listOf(first, newest), versions, "the base never went back: $versions")
        assertEquals(newest, o.reviewing.body!!.resourceVersion())
        assertEquals(mapOf("phase" to "Healthy"), o.reviewing.body!!["status"])
        assertNull(o.session.banner.value)
    }

    @Test
    fun `a poll tick that finds a read in progress is skipped`() = runTest {
        mock.seedConfigMap()
        val gate = GatedIo(testScheduler)
        val o = open(io = gate)
        o.edit("app: demo", "app: edited")

        gate.holdAfter()
        val requests = mock.recorded {
            review(o) // the review's GET is held at the gate, with the lock
            assertEquals(1, gate.pending)
            tick()
            assertEquals(1, gate.pending, "the tick did not start a second read")
            gate.release()
            runCurrent()
        }

        assertEquals(listOf("GET"), requests.filter { it.method == "GET" }.map { it.method }, "one GET for the review and none for the tick: $requests")
    }

    // ── Closing ────────────────────────────────────────────────────────────────

    @Test
    fun `requestClose on a dirty buffer raises the prompt and on a clean one closes`() = runTest {
        mock.seedConfigMap()
        val dirty = open()
        dirty.buffer.type("# unsaved\n")

        dirty.session.requestClose()

        assertTrue(dirty.session.closePrompt.value)
        assertTrue(dirty.closed.isEmpty())
        dirty.session.cancelClose()
        assertFalse(dirty.session.closePrompt.value)
        dirty.session.requestClose()
        dirty.session.confirmClose()
        assertEquals(listOf(7L), dirty.closed)
        assertFalse(dirty.session.closePrompt.value)
        dirty.session.close()
        assertEquals(listOf(7L), dirty.closed, "close is idempotent: onClosed runs once")

        val clean = open()
        clean.session.requestClose()
        assertEquals(listOf(7L), clean.closed)
        assertFalse(clean.session.closePrompt.value)
    }

    @Test
    fun `isDirtyNow is true right after a keystroke while the debounced flow still lags`() = runTest {
        mock.seedConfigMap()
        val o = open()
        assertFalse(o.session.isDirtyNow())

        o.buffer.type(" ")

        assertTrue(o.session.isDirtyNow(), "the live buffer decides, with no time advanced")
        assertFalse(o.session.dirty.value, "the display flow is debounced")
        o.session.requestClose()
        assertTrue(o.session.closePrompt.value, "a quick Cmd+W still gets its prompt")
        assertTrue(o.closed.isEmpty())
        advanceTimeBy(300)
        runCurrent()
        assertTrue(o.session.dirty.value)
    }

    @Test
    fun `isDirtyNow goes false again when the text returns to the seed`() = runTest {
        mock.seedConfigMap()
        val o = open()
        val seeded = o.buffer.text()

        o.buffer.type("x")
        assertTrue(o.session.isDirtyNow())
        o.buffer.type(seeded, replace = true)

        assertFalse(o.session.isDirtyNow())
    }

    @Test
    fun `close cancels the poll and does not cancel the injected scope`() = runTest {
        mock.seedConfigMap()
        val o = open()
        val running = mock.recorded { tick(3) }
        assertEquals(listOf("GET", "GET", "GET"), running.map { it.method }, "positive control: an open session polls every interval")

        o.session.close()

        val afterClose = mock.recorded {
            advanceTimeBy(3 * POLL)
            runCurrent()
        }
        assertEquals(emptyList(), afterClose.map { it.toString() })
        assertTrue(backgroundScope.isActive, "the session's own job is cancelled, never the scope it was given")
        assertEquals(listOf(7L), o.closed)
        o.session.review() // a closed session ignores commands
        runCurrent()
        assertEquals(emptyList(), mock.drain().map { it.toString() })
    }

    @Test
    fun `focus requests count up and the labels name the object`() = runTest {
        val o = open(start = false)

        o.session.requestFocus()
        o.session.requestFocus()

        assertEquals(2, o.session.focusRequests.value)
        assertEquals("Edit ConfigMap $TEST_NAMESPACE/demo-cm — $context", o.session.title)
        assertEquals("ConfigMap $TEST_NAMESPACE/demo-cm", o.session.dirtyLabel)
        assertFalse(o.session.isSecret)
        assertEquals(EditPhase.Loading, o.session.phase.value)
    }

    // ── Secrets ────────────────────────────────────────────────────────────────

    private val sentinel = "hunter2-sentinel-value"
    private val sentinelBase64 = Base64.getEncoder().encodeToString(sentinel.toByteArray())
    private val editedSentinel = "hunter3-sentinel-value"
    private val editedSentinelBase64 = Base64.getEncoder().encodeToString(editedSentinel.toByteArray())

    private fun secretRejection() = failure(422, "Secret \"demo-secret\" is invalid: data[password]: Invalid value: \"$editedSentinelBase64\" (was \"$sentinelBase64\", decoded $editedSentinel)")

    @Test
    fun `a Secret dry-run message is scrubbed while masking is on`() = runTest {
        seedSecret("password" to sentinelBase64)
        mock.server.expect().put().withPath(dryRunPath(secretPath)).andReturn(422, secretRejection()).once()
        val o = open(target = secretTarget, masking = { true })
        assertTrue(o.session.isSecret)
        assertTrue(sentinelBase64 in o.buffer.text(), "the editor shows the raw base64: masking stays on elsewhere, the person consented to the editor")
        o.edit(sentinelBase64, editedSentinelBase64)

        review(o)

        val failed = assertIs<DryRunState.Failed>(o.reviewing.dryRun)
        assertTrue(SecretYamlMasking.PLACEHOLDER in failed.message, failed.message)
        for (value in listOf(sentinelBase64, editedSentinelBase64, sentinel, editedSentinel)) {
            assertFalse(value in failed.message, "'$value' leaked into: ${failed.message}")
        }
        assertTrue(failed.message.startsWith("Secret \"demo-secret\" is invalid"), "the rest of the message stays: ${failed.message}")
    }

    @Test
    fun `a Secret dry-run message is verbatim when masking is off`() = runTest {
        seedSecret("password" to sentinelBase64)
        mock.server.expect().put().withPath(dryRunPath(secretPath)).andReturn(422, secretRejection()).once()
        val o = open(target = secretTarget, masking = { false })
        o.edit(sentinelBase64, editedSentinelBase64)

        review(o)

        val failed = assertIs<DryRunState.Failed>(o.reviewing.dryRun)
        assertTrue(editedSentinelBase64 in failed.message, "negative control: without masking nothing is scrubbed")
        assertFalse(SecretYamlMasking.PLACEHOLDER in failed.message)
    }

    @Test
    fun `a Secret apply failure is scrubbed while masking is on`() = runTest {
        seedSecret("password" to sentinelBase64)
        mock.server.expect().put().withPath(dryRunPath(secretPath)).andReturn(200, """{"apiVersion":"v1","kind":"Secret","metadata":{"name":"demo-secret","resourceVersion":"99"}}""").always()
        mock.server.expect().put().withPath(replacePath(secretPath)).andReturn(403, failure(403, "cannot update; value was $editedSentinelBase64")).once()
        val o = open(target = secretTarget, masking = { true })
        o.edit(sentinelBase64, editedSentinelBase64)
        review(o)
        o.session.requestApply()

        o.session.confirmApply()
        runCurrent()

        val failed = assertIs<ApplyState.Failed>(o.session.apply.value)
        assertFalse(editedSentinelBase64 in failed.message, failed.message)
        assertTrue(SecretYamlMasking.PLACEHOLDER in failed.message)
    }

    // ── Logging ────────────────────────────────────────────────────────────────

    @Test
    fun `the session logs no YAML, no message and no secret, only ids, names and a class name`() = runTest {
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            // Positive control: the appender records, so silence below means something.
            LoggerFactory.getLogger(YamlEditSession::class.java).info("probe-event")
            assertTrue(appender.list.any { it.formattedMessage == "probe-event" })

            seedSecret("password" to sentinelBase64)
            mock.server.expect().put().withPath(dryRunPath(secretPath)).andReturn(200, """{"apiVersion":"v1","kind":"Secret","metadata":{"name":"demo-secret","resourceVersion":"99"}}""").always()
            mock.server.expect().put().withPath(replacePath(secretPath)).andReturn(422, secretRejection()).once()
            val feedback = object : ActionFeedback {
                override fun success(title: String, detail: String?, undo: UndoAction?): Long = error(sentinel)

                override fun failure(title: String, detail: String?): Long = 0

                override fun warning(title: String, detail: String?): Long = 0

                override fun info(title: String, detail: String?): Long = 0
            }
            val closed = mutableListOf<Long>()
            val buffer = StringEditorBuffer()
            val session = YamlEditSession(7, SessionId("tab-1"), context, secretTarget, YamlWriter(mock.manager), feedback, { true }, buffer, backgroundScope, StandardTestDispatcher(testScheduler), StandardTestDispatcher(testScheduler), POLL, { closed += it })
            session.start()
            runCurrent()
            buffer.type(buffer.text().replace(sentinelBase64, editedSentinelBase64), replace = true)
            session.review()
            runCurrent()
            session.requestApply()
            session.confirmApply()
            runCurrent() // the PUT fails with a 422 that quotes the secret: Failed, logged by the writer
            session.cancelApply()
            // Now an apply that succeeds, whose toast throws: the handler logs the class name only.
            mock.server.expect().put().withPath(replacePath(secretPath)).andReturn(200, """{"apiVersion":"v1","kind":"Secret","metadata":{"name":"demo-secret","resourceVersion":"100"}}""").once()
            session.requestApply()
            session.confirmApply()
            runCurrent()

            assertEquals(listOf(7L), closed, "a failing toast still closes the session")
            val sessionEvents = appender.list.filter { it.loggerName == YamlEditSession::class.java.name && it.formattedMessage != "probe-event" }
            assertEquals(
                listOf(
                    "Edit session 7 opened Secret demo-secret ns=$TEST_NAMESPACE",
                    "Edit session 7 closed",
                    "Edit session 7 failed: IllegalStateException",
                ),
                sessionEvents.map { it.formattedMessage },
            )
            for (event in appender.list) {
                val texts = listOfNotNull(event.formattedMessage, event.message, event.throwableProxy?.message, event.argumentArray?.joinToString { it.toString() })
                for (text in texts) {
                    for (secret in listOf(sentinel, sentinelBase64, editedSentinel, editedSentinelBase64)) {
                        assertFalse(secret in text, "a logged event carries the secret: $text")
                    }
                    assertFalse("kind: Secret" in text || "apiVersion" in text, "a logged event carries YAML: $text")
                }
            }
            assertTrue(sessionEvents.all { it.throwableProxy == null }, "the session never hands an exception to the logger")
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    private companion object {
        const val POLL = 5_000L
    }
}
