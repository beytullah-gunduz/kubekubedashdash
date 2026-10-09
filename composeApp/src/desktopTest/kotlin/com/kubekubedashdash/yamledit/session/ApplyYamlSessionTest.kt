package com.kubekubedashdash.yamledit.session

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.ui.feedback.ActionFeedback
import com.kubekubedashdash.ui.feedback.UndoAction
import com.kubekubedashdash.util.SecretYamlMasking
import com.kubekubedashdash.yamledit.ApplyOutcome
import com.kubekubedashdash.yamledit.Sent
import com.kubekubedashdash.yamledit.TEST_NAMESPACE
import com.kubekubedashdash.yamledit.WriterMock
import com.kubekubedashdash.yamledit.YamlWriter
import io.fabric8.kubernetes.api.model.APIResourceBuilder
import io.fabric8.kubernetes.api.model.APIResourceListBuilder
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Apply YAML session against the fabric8 CRUD mock the demo cluster runs on. The real cluster
 * path (server-side-apply dry runs and applies) answers from mock expectations, because the mock
 * has no server-side apply and would persist a dry run; the demo path (`demoMode = { true }`)
 * uses the CRUD store itself. The session runs on `backgroundScope`; `io` and `compute` are test
 * dispatchers on the same scheduler, so the blocking mock calls run on the test thread. Loopback
 * only; no real user state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApplyYamlSessionTest {

    private val context = "cluster-a"
    private lateinit var mock: WriterMock

    @BeforeTest
    fun setUp() {
        mock = WriterMock(context)
    }

    @AfterTest
    fun tearDown() {
        mock.stop("ApplyYamlSessionTest")
    }

    private val realWriter get() = YamlWriter(mock.manager)
    private val demoWriter get() = YamlWriter(mock.manager, demoMode = { true })

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private class Opened(val session: ApplyYamlSession, val buffer: StringEditorBuffer, val feedback: RecordingFeedback, val closed: MutableList<Long>) {
        val reviewing: ApplyPhase.Reviewing get() = assertIs<ApplyPhase.Reviewing>(session.phase.value)
        val done: ApplyPhase.Done get() = assertIs<ApplyPhase.Done>(session.phase.value)

        val rows: List<DocRow>
            get() = when (val phase = session.phase.value) {
                is ApplyPhase.Reviewing -> phase.rows
                is ApplyPhase.Applying -> phase.rows
                is ApplyPhase.Done -> phase.rows
                ApplyPhase.Editing -> emptyList()
            }

        val statuses: List<DocStatus> get() = rows.map { it.status }
    }

    private fun TestScope.open(
        text: String,
        writer: YamlWriter = realWriter,
        masking: () -> Boolean = { false },
        crds: List<CrdInfo> = emptyList(),
        defaultNamespace: String = TEST_NAMESPACE,
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        feedback: RecordingFeedback = RecordingFeedback(),
        sessionContext: String = context,
    ): Opened {
        val buffer = StringEditorBuffer(text)
        val closed = mutableListOf<Long>()
        val session = ApplyYamlSession(
            id = 9,
            clusterSessionId = SessionId("tab-1"),
            context = sessionContext,
            defaultNamespace = defaultNamespace,
            writer = writer,
            crds = { crds },
            feedback = feedback,
            masking = masking,
            buffer = buffer,
            scope = backgroundScope,
            io = io,
            compute = StandardTestDispatcher(testScheduler),
            onClosed = { closed += it },
        )
        return Opened(session, buffer, feedback, closed)
    }

    private fun TestScope.review(o: Opened) {
        o.session.review()
        runCurrent()
    }

    private fun TestScope.confirm(o: Opened) {
        o.session.confirmApply()
        runCurrent()
    }

    private fun configMap(name: String, namespace: String? = TEST_NAMESPACE, value: String = "1") = buildString {
        appendLine("apiVersion: v1")
        appendLine("kind: ConfigMap")
        appendLine("metadata:")
        appendLine("  name: $name")
        if (namespace != null) appendLine("  namespace: $namespace")
        appendLine("data:")
        appendLine("  a: \"$value\"")
    }

    private fun docs(vararg documents: String) = documents.joinToString("---\n")

    private fun failure(code: Int, message: String): Status = StatusBuilder().withCode(code).withStatus("Failure").withReason("Invalid").withMessage(message).build()

    private fun cmPath(name: String) = "/api/v1/namespaces/$TEST_NAMESPACE/configmaps/$name"

    private fun dryRunPath(path: String) = "$path?fieldManager=kubekubedashdash&dryRun=All"

    private fun applyPath(path: String) = "$path?fieldManager=kubekubedashdash"

    private fun configMapJson(name: String) = """{"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"$name","namespace":"$TEST_NAMESPACE","resourceVersion":"1"}}"""

    /** The real cluster's dry run of each ConfigMap in [names]: passes. */
    private fun expectDryRunsPass(vararg names: String) {
        for (name in names) mock.server.expect().patch().withPath(dryRunPath(cmPath(name))).andReturn(200, configMapJson(name)).always()
    }

    private fun expectApplySucceeds(name: String) {
        mock.server.expect().patch().withPath(applyPath(cmPath(name))).andReturn(200, configMapJson(name)).once()
    }

    private val List<Sent>.summary get() = map { "${it.method} ${it.path}" }

    private fun crd(group: String, kind: String, plural: String, scope: CrdScope = CrdScope.NAMESPACED) = CrdInfo(group, "v1", kind, plural, kind.lowercase(), emptyList(), emptyList(), scope, emptyList())

    private val widgetText = """
        apiVersion: example.com/v1
        kind: Widget
        metadata:
          name: w1
          namespace: $TEST_NAMESPACE
        spec:
          size: 2
    """.trimIndent() + "\n"

    // ── Review ─────────────────────────────────────────────────────────────────

    @Test
    fun `a review dry-runs every document with a server-side apply and plans each one`() = runTest {
        expectDryRunsPass("cm-a", "cm-b")
        val o = open(docs(configMap("cm-a"), configMap("cm-b")))

        val requests = mock.recorded { review(o) }

        assertEquals(listOf(DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = false), DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = false)), o.statuses)
        assertFalse(o.reviewing.running)
        assertEquals(listOf("ConfigMap $TEST_NAMESPACE/cm-a", "ConfigMap $TEST_NAMESPACE/cm-b"), o.rows.map { it.label })
        assertEquals(
            listOf("GET ${cmPath("cm-a")}", "PATCH ${dryRunPath(cmPath("cm-a"))}", "GET ${cmPath("cm-b")}", "PATCH ${dryRunPath(cmPath("cm-b"))}"),
            requests.summary,
            "the documents are checked in input order, each with its own GET and dry-run PATCH",
        )
        assertTrue(requests.filter { it.method == "PATCH" }.all { it.contentType?.substringBefore(';') == "application/apply-patch+yaml" })
        assertTrue(o.session.canApply.value)
        assertNull(o.session.parseProblem.value)
        assertTrue(o.session.problems.value.isEmpty())
    }

    @Test
    fun `the rows carry the start line of their document`() = runTest {
        expectDryRunsPass("cm-a", "cm-b")
        val o = open(docs(configMap("cm-a"), configMap("cm-b")))

        review(o)

        val firstLines = configMap("cm-a").lines().size - 1
        assertEquals(listOf(1, firstLines + 2), o.rows.map { it.line }, "the second document starts after the separator")
        assertEquals(listOf(0, 1), o.rows.map { it.index })
    }

    @Test
    fun `a kind List is expanded into one row per item`() = runTest {
        val o = open(
            """
            apiVersion: v1
            kind: List
            items:
              - apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: cm-a
                  namespace: $TEST_NAMESPACE
              - apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: cm-b
                  namespace: $TEST_NAMESPACE
            """.trimIndent() + "\n",
            writer = demoWriter,
        )

        review(o)

        assertEquals(listOf("ConfigMap $TEST_NAMESPACE/cm-a", "ConfigMap $TEST_NAMESPACE/cm-b"), o.rows.map { it.label })
        assertTrue(o.session.canApply.value)
    }

    @Test
    fun `rows are published while the dry runs are still running`() = runTest {
        expectDryRunsPass("cm-a", "cm-b")
        val gate = GatedIo(testScheduler)
        val o = open(docs(configMap("cm-a"), configMap("cm-b")), io = gate)

        gate.holdAfter(1) // the preparation passes, the first dry run waits
        review(o)

        assertTrue(o.reviewing.running)
        assertEquals(listOf(DocStatus.Pending, DocStatus.Pending), o.statuses)
        assertFalse(o.session.canApply.value, "nothing can be applied before every document has an answer")
        o.session.confirmApply()
        runCurrent()
        assertIs<ApplyPhase.Reviewing>(o.session.phase.value)

        gate.release()
        runCurrent()

        assertFalse(o.reviewing.running)
        assertTrue(o.session.canApply.value)
    }

    @Test
    fun `a failing dry run on any document blocks apply and sends nothing`() = runTest {
        expectDryRunsPass("cm-a")
        mock.server.expect().patch().withPath(dryRunPath(cmPath("cm-b"))).andReturn(422, failure(422, "ConfigMap \"cm-b\" is invalid: data[a]: Invalid value")).once()
        val o = open(docs(configMap("cm-a"), configMap("cm-b")))
        review(o)

        assertEquals(DocStatus.Planned(ApplyOutcome.Created, false), o.statuses[0])
        assertEquals(DocStatus.Failed("ConfigMap \"cm-b\" is invalid: data[a]: Invalid value"), o.statuses[1])
        assertFalse(o.session.canApply.value)
        val requests = mock.recorded { confirm(o) }

        assertEquals(emptyList(), requests.summary, "Apply is disabled, so confirming it does nothing")
        assertIs<ApplyPhase.Reviewing>(o.session.phase.value)
        assertTrue(o.feedback.events.isEmpty())
    }

    @Test
    fun `an apply of documents that would all stay unchanged is not offered`() = runTest {
        mock.seedConfigMap("demo-cm", mapOf("a" to "1"))
        val o = open(configMap("demo-cm"), writer = demoWriter)

        review(o)

        assertEquals(listOf<DocStatus>(DocStatus.Planned(ApplyOutcome.Unchanged, simulatedLocally = true)), o.statuses)
        assertFalse(o.session.canApply.value, "at least one document must create or change something")
    }

    @Test
    fun `a document the cluster rejects at the dry run shows the server's message`() = runTest {
        val message = "configmaps \"cm-a\" is forbidden: User \"dev@example.com\" cannot patch resource \"configmaps\""
        mock.server.expect().patch().withPath(dryRunPath(cmPath("cm-a"))).andReturn(403, failure(403, message)).once()
        val o = open(configMap("cm-a"))

        review(o)

        assertEquals(DocStatus.Failed("Permission denied (403): $message"), o.statuses.single())
        assertFalse(o.session.canApply.value)
    }

    @Test
    fun `a session whose cluster the tab no longer shows fails every document without a request`() = runTest {
        val o = open(configMap("cm-a"), sessionContext = "cluster-b")

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.summary)
        val failed = assertIs<DocStatus.Failed>(o.statuses.single())
        assertTrue("cluster-b" in failed.message, failed.message)
        assertFalse(o.session.canApply.value)
    }

    // ── Local checks: no request leaves ───────────────────────────────────────

    @Test
    fun `a mask token in any document stops the review before a single request`() = runTest {
        val masked = configMap("cm-b", value = SecretYamlMasking.PLACEHOLDER)
        val text = docs(configMap("cm-a"), masked)
        val o = open(text)

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.summary)
        assertEquals(ApplyPhase.Editing, o.session.phase.value)
        val problem = o.session.problems.value.single()
        assertTrue(SecretYamlMasking.PLACEHOLDER in problem.message)
        assertEquals(text.lines().indexOfFirst { SecretYamlMasking.PLACEHOLDER in it } + 1, problem.line)
        assertFalse(o.session.canApply.value)
    }

    @Test
    fun `a revealed binary placeholder is a mask token too`() = runTest {
        val o = open(configMap("cm-a", value = "<binary: 12 bytes>"))

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.summary)
        assertEquals(1, o.session.problems.value.size)
    }

    @Test
    fun `a YAML error is a parseProblem with line and column and costs no request`() = runTest {
        val o = open("apiVersion: v1\nkind: ConfigMap\nkind: Secret\n")

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.summary)
        assertEquals(ApplyPhase.Editing, o.session.phase.value)
        val problem = assertNotNull(o.session.parseProblem.value)
        assertEquals(3, problem.line, "the duplicate key is on line 3")
        assertTrue(problem.column >= 1)
    }

    @Test
    fun `a recursive alias is a parse problem at its document's line and costs no request`() = runTest {
        val o = open(docs(configMap("cm-a"), "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm-b\n  labels: &l {app: demo, self: *l}\n"))

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.summary)
        assertEquals(ApplyPhase.Editing, o.session.phase.value)
        val problem = assertNotNull(o.session.parseProblem.value)
        assertEquals("Recursive YAML aliases are not supported.", problem.message)
        assertEquals(configMap("cm-a").lines().size + 1, problem.line, "the second document starts after the separator")
        assertTrue(o.session.problems.value.isEmpty())
    }

    @Test
    fun `an Error thrown inside the review is shown as a problem, not swallowed`() = runTest {
        val buffer = ErroringBuffer(StringEditorBuffer(configMap("cm-a")))
        val session = ApplyYamlSession(
            id = 9,
            clusterSessionId = SessionId("tab-1"),
            context = context,
            defaultNamespace = TEST_NAMESPACE,
            writer = realWriter,
            crds = { emptyList() },
            feedback = RecordingFeedback(),
            masking = { false },
            buffer = buffer,
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            compute = StandardTestDispatcher(testScheduler),
            onClosed = {},
        )

        buffer.failing = true
        session.review()
        runCurrent()
        buffer.failing = false

        assertEquals("Unexpected error: StackOverflowError", session.problems.value.single().message)
        assertEquals(ApplyPhase.Editing, session.phase.value)
    }

    @Test
    fun `an empty buffer has nothing to apply`() = runTest {
        val o = open("")

        review(o)

        assertEquals("Nothing to apply.", assertNotNull(o.session.parseProblem.value).message)
        assertEquals(ApplyPhase.Editing, o.session.phase.value)
    }

    @Test
    fun `a document that cannot be prepared is a Failed row and the others are still dry-run`() = runTest {
        val unknown = "apiVersion: v1\nkind: Frobnicator\nmetadata:\n  name: f1\n  namespace: $TEST_NAMESPACE\n"
        val o = open(docs(configMap("cm-a"), unknown, configMap("cm-c")), writer = demoWriter)

        review(o)

        assertEquals(listOf(0, 1, 2), o.rows.map { it.index })
        assertEquals(
            listOf<DocStatus>(
                DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = true),
                DocStatus.Failed("Unknown kind v1 Frobnicator on this cluster."),
                DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = true),
            ),
            o.statuses,
        )
        assertEquals("Frobnicator $TEST_NAMESPACE/f1", o.rows[1].label)
        assertEquals(listOf(1, 9, 15), o.rows.map { it.line }, "each row points at its own document, the failed one included")
        assertFalse(o.session.canApply.value, "one failed document blocks the apply of all")
    }

    @Test
    fun `a document without a namespace gets the default one and says so`() = runTest {
        val o = open(docs(configMap("cm-a", namespace = null), configMap("cm-b", namespace = "other-ns")), writer = demoWriter)

        val requests = mock.recorded { review(o) }

        assertEquals(listOf(true, false), o.rows.map { it.namespaceDefaulted })
        assertEquals(listOf("ConfigMap $TEST_NAMESPACE/cm-a", "ConfigMap other-ns/cm-b"), o.rows.map { it.label })
        assertEquals(listOf("GET ${cmPath("cm-a")}", "GET /api/v1/namespaces/other-ns/configmaps/cm-b"), requests.summary)
    }

    // ── Kind resolution ────────────────────────────────────────────────────────

    @Test
    fun `a built-in kind uses the version the document names and never asks discovery`() = runTest {
        val hpa = "apiVersion: autoscaling/v1\nkind: HorizontalPodAutoscaler\nmetadata:\n  name: hpa-1\n  namespace: $TEST_NAMESPACE\n"
        val o = open(hpa, writer = realWriter)
        val path = "/apis/autoscaling/v1/namespaces/$TEST_NAMESPACE/horizontalpodautoscalers/hpa-1"
        mock.server.expect().patch().withPath(dryRunPath(path)).andReturn(200, """{"apiVersion":"autoscaling/v1","kind":"HorizontalPodAutoscaler","metadata":{"name":"hpa-1"}}""").once()

        val requests = mock.recorded { review(o) }

        assertEquals(listOf("GET $path", "PATCH ${dryRunPath(path)}"), requests.summary)
        assertEquals(DocStatus.Planned(ApplyOutcome.Created, false), o.statuses.single())
    }

    @Test
    fun `a kind of this tab's CRDs is resolved from them and never asks discovery`() = runTest {
        val path = "/apis/example.com/v1/namespaces/$TEST_NAMESPACE/widgets/w1"
        mock.server.expect().patch().withPath(dryRunPath(path)).andReturn(200, """{"apiVersion":"example.com/v1","kind":"Widget","metadata":{"name":"w1"}}""").once()
        val o = open(widgetText, crds = listOf(crd("example.com", "Widget", "widgets")))

        val requests = mock.recorded { review(o) }

        assertEquals(listOf("GET $path", "PATCH ${dryRunPath(path)}"), requests.summary)
        assertEquals(DocStatus.Planned(ApplyOutcome.Created, false), o.statuses.single())
    }

    @Test
    fun `an unknown kind on a real cluster is resolved by discovery once per apiVersion and kind`() = runTest {
        mock.server.expect().get().withPath("/apis/example.com/v1").andReturn(
            200,
            APIResourceListBuilder().withGroupVersion("example.com/v1")
                .withResources(APIResourceBuilder().withName("gadgets").withKind("Gadget").withNamespaced(false).build())
                .build(),
        ).always()
        for (name in listOf("g1", "g2")) {
            mock.server.expect().patch().withPath(dryRunPath("/apis/example.com/v1/gadgets/$name")).andReturn(200, """{"apiVersion":"example.com/v1","kind":"Gadget","metadata":{"name":"$name"}}""").once()
        }
        val gadget = { name: String -> "apiVersion: example.com/v1\nkind: Gadget\nmetadata:\n  name: $name\n" }
        val o = open(docs(gadget("g1"), gadget("g2")))

        val requests = mock.recorded { review(o) }

        assertEquals(1, requests.count { it.path == "/apis/example.com/v1" }, "the second Gadget reuses the first answer")
        assertEquals(listOf("Gadget g1", "Gadget g2"), o.rows.map { it.label }, "a cluster-scoped kind has no namespace")
        assertTrue(o.statuses.all { it is DocStatus.Planned })
    }

    @Test
    fun `a kind the cluster does not know is an unknown-kind row`() = runTest {
        mock.server.expect().get().withPath("/apis/example.com/v1").andReturn(200, APIResourceListBuilder().withGroupVersion("example.com/v1").build()).once()
        val o = open("apiVersion: example.com/v1\nkind: Sprocket\nmetadata:\n  name: s1\n")

        review(o)

        assertEquals(DocStatus.Failed("Unknown kind example.com/v1 Sprocket on this cluster."), o.statuses.single())
    }

    @Test
    fun `the demo cluster never asks discovery`() = runTest {
        val o = open(widgetText, writer = demoWriter)

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.summary)
        assertEquals(DocStatus.Failed("Unknown kind example.com/v1 Widget on this cluster."), o.statuses.single())
    }

    @Test
    fun `an apiVersion that is not a plain path segment pair is an unknown kind and costs no request`() = runTest {
        val o = open("apiVersion: apps/v1/../../x\nkind: Deployment\nmetadata:\n  name: web\n  namespace: $TEST_NAMESPACE\n")

        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.summary)
        assertIs<DocStatus.Failed>(o.statuses.single())
    }

    @Test
    fun `an apiVersion with a dot segment is an unknown kind and costs no request`() = runTest {
        for (apiVersion in listOf("apps/..", "../v1", "..", "./v1", "apps/.")) {
            val o = open("apiVersion: $apiVersion\nkind: Deployment\nmetadata:\n  name: web\n  namespace: $TEST_NAMESPACE\n")

            val requests = mock.recorded { review(o) }

            assertEquals(emptyList(), requests.summary, apiVersion)
            assertEquals(DocStatus.Failed("Unknown kind $apiVersion Deployment on this cluster."), o.statuses.single(), apiVersion)
        }
    }

    @Test
    fun `a discovery failure stops the review with a problem and stays in the editor`() = runTest {
        mock.server.expect().get().withPath("/apis/broken.example.com/v1").andReturn(405, failure(405, "no discovery here")).once()
        val o = open("apiVersion: broken.example.com/v1\nkind: Widget\nmetadata:\n  name: w1\n")

        review(o)

        assertEquals(ApplyPhase.Editing, o.session.phase.value)
        assertEquals("Couldn't reach the cluster: no discovery here", o.session.problems.value.single().message)
    }

    // ── Apply ──────────────────────────────────────────────────────────────────

    @Test
    fun `a multi-document apply continues after a failure and reports each document`() = runTest {
        expectDryRunsPass("cm-a", "cm-b", "cm-c")
        expectApplySucceeds("cm-a")
        mock.server.expect().patch().withPath(applyPath(cmPath("cm-b"))).andReturn(403, failure(403, "configmaps \"cm-b\" is forbidden")).once()
        expectApplySucceeds("cm-c")
        val text = docs(configMap("cm-a"), configMap("cm-b"), configMap("cm-c"))
        val o = open(text)
        review(o)
        assertTrue(o.session.canApply.value)

        val requests = mock.recorded { confirm(o) }

        assertEquals(
            listOf(
                DocStatus.Applied(ApplyOutcome.Created),
                DocStatus.Failed("Permission denied (403): configmaps \"cm-b\" is forbidden"),
                DocStatus.Applied(ApplyOutcome.Created),
            ),
            o.done.rows.map { it.status },
        )
        assertEquals(
            listOf(
                "GET ${cmPath("cm-a")}",
                "PATCH ${applyPath(cmPath("cm-a"))}",
                "GET ${cmPath("cm-b")}",
                "PATCH ${applyPath(cmPath("cm-b"))}",
                "GET ${cmPath("cm-c")}",
                "PATCH ${applyPath(cmPath("cm-c"))}",
            ),
            requests.summary,
            "in input order, and cm-c is still applied after cm-b failed",
        )
        assertTrue(requests.filter { it.method == "PATCH" }.none { "dryRun" in it.path })
        val toast = o.feedback.events.single()
        assertEquals("warning", toast.kind)
        assertEquals("Applied 2 of 3 documents", toast.title)
        assertEquals("Nothing was rolled back: documents applied before the failure stay applied.", toast.detail)
        assertFalse(o.session.canApply.value)
        assertTrue(o.session.isDirtyNow(), "a failed document leaves the text unapplied")
        assertTrue(o.closed.isEmpty(), "the window stays open to show the result")
    }

    @Test
    fun `when every document applies the toast says so and the text is no longer unsaved work`() = runTest {
        expectDryRunsPass("cm-a", "cm-b")
        expectApplySucceeds("cm-a")
        expectApplySucceeds("cm-b")
        val o = open(docs(configMap("cm-a"), configMap("cm-b")))
        review(o)
        assertTrue(o.session.isDirtyNow())

        confirm(o)

        assertEquals(listOf<DocStatus>(DocStatus.Applied(ApplyOutcome.Created), DocStatus.Applied(ApplyOutcome.Created)), o.done.rows.map { it.status })
        val toast = o.feedback.events.single()
        assertEquals("success", toast.kind)
        assertEquals("Applied 2 document(s) to $context", toast.title)
        assertNull(toast.detail)
        assertFalse(o.session.isDirtyNow())
        assertFalse(o.session.dirty.value, "the display flag follows at once")
        o.session.requestClose()
        assertEquals(listOf(9L), o.closed, "an applied window closes without a prompt")
    }

    @Test
    fun `typing after a fully applied text is unsaved work again, and restoring it is not`() = runTest {
        expectDryRunsPass("cm-a")
        expectApplySucceeds("cm-a")
        val text = configMap("cm-a")
        val o = open(text)
        review(o)
        confirm(o)
        assertFalse(o.session.isDirtyNow())

        o.buffer.type("# more\n")
        assertTrue(o.session.isDirtyNow())
        o.buffer.type(text, replace = true)
        assertFalse(o.session.isDirtyNow())
    }

    @Test
    fun `a document found unchanged is marked applied without a request and the rest are applied`() = runTest {
        mock.seedConfigMap("demo-cm", mapOf("a" to "1"))
        val o = open(docs(configMap("demo-cm"), configMap("new-cm")), writer = demoWriter)
        review(o)
        assertEquals(listOf(ApplyOutcome.Unchanged, ApplyOutcome.Created), o.statuses.map { (it as DocStatus.Planned).outcome })
        assertTrue(o.session.canApply.value)

        val requests = mock.recorded { confirm(o) }

        assertEquals(listOf<DocStatus>(DocStatus.Applied(ApplyOutcome.Unchanged), DocStatus.Applied(ApplyOutcome.Created)), o.done.rows.map { it.status })
        assertEquals(listOf("GET ${cmPath("new-cm")}", "POST /api/v1/namespaces/$TEST_NAMESPACE/configmaps?fieldManager=kubekubedashdash"), requests.summary, "nothing at all is sent for the unchanged document")
        assertEquals("success", o.feedback.events.single().kind)
    }

    @Test
    fun `on the demo cluster the review only reads and the apply creates with a POST`() = runTest {
        val o = open(configMap("new-cm", value = "9"), writer = demoWriter)

        val reviewRequests = mock.recorded { review(o) }

        assertEquals(listOf("GET ${cmPath("new-cm")}"), reviewRequests.summary, "no dry run is ever sent to the demo mock: it would persist it")
        assertEquals(DocStatus.Planned(ApplyOutcome.Created, simulatedLocally = true), o.statuses.single())
        assertNull(mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("new-cm").get(), "reviewing created nothing")

        val applyRequests = mock.recorded { confirm(o) }

        assertEquals(listOf("GET ${cmPath("new-cm")}", "POST /api/v1/namespaces/$TEST_NAMESPACE/configmaps?fieldManager=kubekubedashdash"), applyRequests.summary)
        assertEquals(DocStatus.Applied(ApplyOutcome.Created), o.done.rows.single().status)
        assertEquals("9", mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("new-cm").get().data["a"])
    }

    @Test
    fun `on the demo cluster a present object is merged and replaced`() = runTest {
        mock.seedConfigMap("demo-cm", mapOf("a" to "1"))
        val o = open(configMap("demo-cm", value = "2"), writer = demoWriter)
        review(o)
        assertEquals(DocStatus.Planned(ApplyOutcome.Configured, simulatedLocally = true), o.statuses.single())

        val requests = mock.recorded { confirm(o) }

        assertEquals(listOf("GET", "PUT"), requests.map { it.method })
        assertEquals(DocStatus.Applied(ApplyOutcome.Configured), o.done.rows.single().status)
        assertEquals("2", mock.seed.configMaps().inNamespace(TEST_NAMESPACE).withName("demo-cm").get().data["a"])
    }

    @Test
    fun `confirmApply does nothing before a review, and a second confirm does nothing after`() = runTest {
        expectDryRunsPass("cm-a")
        expectApplySucceeds("cm-a")
        val o = open(configMap("cm-a"))

        val before = mock.recorded { confirm(o) }
        assertEquals(emptyList(), before.summary)
        assertEquals(ApplyPhase.Editing, o.session.phase.value)

        review(o)
        confirm(o)
        val after = mock.recorded { confirm(o) }

        assertEquals(emptyList(), after.summary, "Done is not applicable: the documents were applied once")
        assertEquals(1, o.feedback.events.size)
    }

    @Test
    fun `text typed after the review is never applied`() = runTest {
        expectDryRunsPass("cm-a")
        val o = open(configMap("cm-a"))
        review(o)
        assertTrue(o.session.canApply.value)
        o.buffer.type("# changed after the dry run\n")

        val requests = mock.recorded { confirm(o) }

        assertEquals(emptyList(), requests.summary)
        assertEquals(ApplyPhase.Editing, o.session.phase.value)
        assertTrue(o.session.problems.value.single().message.contains("changed"))
        assertFalse(o.session.canApply.value)
    }

    @Test
    fun `an apply that reports a different outcome than planned shows the one it got`() = runTest {
        // Planned as Created (absent at review); somebody created it meanwhile, so the apply finds it and merges.
        expectDryRunsPass("cm-a")
        val o = open(configMap("cm-a"))
        review(o)
        mock.seedConfigMap("cm-a", mapOf("a" to "other"))
        mock.server.expect().patch().withPath(applyPath(cmPath("cm-a"))).andReturn(200, """{"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"cm-a","resourceVersion":"2"},"data":{"a":"changed"}}""").once()

        confirm(o)

        assertEquals(DocStatus.Applied(ApplyOutcome.Configured), o.done.rows.single().status)
    }

    // ── Back to the editor ─────────────────────────────────────────────────────

    @Test
    fun `back to the editor drops the rows and the text can be reviewed again`() = runTest {
        expectDryRunsPass("cm-a")
        val o = open(configMap("cm-a"))
        review(o)
        assertTrue(o.session.canApply.value)

        o.session.backToEditor()

        assertEquals(ApplyPhase.Editing, o.session.phase.value)
        assertFalse(o.session.canApply.value)
        review(o)
        assertTrue(o.session.canApply.value)
    }

    @Test
    fun `back to the editor from the result keeps the text and the unsaved state`() = runTest {
        expectDryRunsPass("cm-a", "cm-b")
        expectApplySucceeds("cm-a")
        mock.server.expect().patch().withPath(applyPath(cmPath("cm-b"))).andReturn(403, failure(403, "forbidden")).once()
        val o = open(docs(configMap("cm-a"), configMap("cm-b")))
        review(o)
        confirm(o)
        assertIs<ApplyPhase.Done>(o.session.phase.value)

        o.session.backToEditor()

        assertEquals(ApplyPhase.Editing, o.session.phase.value)
        assertTrue(o.session.isDirtyNow())
    }

    @Test
    fun `back to the editor during a review cancels it`() = runTest {
        expectDryRunsPass("cm-a", "cm-b")
        val gate = GatedIo(testScheduler)
        val o = open(docs(configMap("cm-a"), configMap("cm-b")), io = gate)
        gate.holdAfter(1)
        review(o)
        assertTrue(o.reviewing.running)

        o.session.backToEditor()
        gate.release()
        runCurrent()

        assertEquals(ApplyPhase.Editing, o.session.phase.value, "the answers of the cancelled review are dropped")
        assertFalse(o.session.canApply.value)
    }

    @Test
    fun `backToEditor and review are ignored in the wrong phase`() = runTest {
        val o = open(configMap("cm-a"))
        o.session.backToEditor()
        assertEquals(ApplyPhase.Editing, o.session.phase.value)

        expectDryRunsPass("cm-a")
        review(o)
        val first = o.session.phase.value
        val requests = mock.recorded { review(o) }

        assertEquals(emptyList(), requests.summary, "review is for the Editing phase only")
        assertEquals(first, o.session.phase.value)
    }

    // ── Dirty and closing ──────────────────────────────────────────────────────

    @Test
    fun `an empty or blank buffer is not unsaved work and a keystroke is, before the debounce`() = runTest {
        val o = open("")
        assertFalse(o.session.isDirtyNow())

        o.buffer.type("  \n")
        assertFalse(o.session.isDirtyNow(), "whitespace is nothing")

        o.buffer.type("a")
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
    fun `typing invalid YAML shows the parse problem after the debounce and clearing the buffer clears it`() = runTest {
        val o = open("")

        o.buffer.type("\n  : : [")
        assertNull(o.session.parseProblem.value, "nothing before the debounce")
        advanceTimeBy(299)
        runCurrent()
        assertNull(o.session.parseProblem.value)
        advanceTimeBy(1)
        runCurrent()
        assertNotNull(o.session.parseProblem.value)

        o.buffer.type("", replace = true)
        advanceTimeBy(300)
        runCurrent()
        assertNull(o.session.parseProblem.value)
        assertFalse(o.session.dirty.value)
    }

    @Test
    fun `requestClose on a clean buffer closes and on a dirty one raises the prompt that cancel and confirm answer`() = runTest {
        val clean = open("")
        clean.session.requestClose()
        assertEquals(listOf(9L), clean.closed)

        val dirty = open(configMap("cm-a"))
        dirty.session.requestClose()
        assertTrue(dirty.session.closePrompt.value)
        assertTrue(dirty.closed.isEmpty())
        dirty.session.cancelClose()
        assertFalse(dirty.session.closePrompt.value)
        dirty.session.requestClose()
        dirty.session.confirmClose()
        assertEquals(listOf(9L), dirty.closed)
        assertFalse(dirty.session.closePrompt.value)
    }

    @Test
    fun `requestClose while the documents are being applied is ignored`() = runTest {
        expectDryRunsPass("cm-a")
        expectApplySucceeds("cm-a")
        val gate = GatedIo(testScheduler)
        val o = open(configMap("cm-a"), io = gate)
        review(o)
        gate.holdAfter(0)
        o.session.confirmApply()
        runCurrent()
        assertIs<ApplyPhase.Applying>(o.session.phase.value)
        assertEquals(1, gate.pending)

        o.session.requestClose()

        assertFalse(o.session.closePrompt.value)
        assertTrue(o.closed.isEmpty(), "closing now would leave the person guessing what was applied")

        gate.release()
        runCurrent()

        assertIs<ApplyPhase.Done>(o.session.phase.value)
        o.session.requestClose()
        assertEquals(listOf(9L), o.closed, "fully applied and unchanged: nothing is unsaved")
    }

    @Test
    fun `closing while documents are being applied warns that some may have landed`() = runTest {
        expectDryRunsPass("cm-a")
        expectApplySucceeds("cm-a")
        val gate = GatedIo(testScheduler)
        val o = open(configMap("cm-a"), io = gate)
        review(o)
        gate.holdAfter(0)
        o.session.confirmApply()
        runCurrent()
        assertIs<ApplyPhase.Applying>(o.session.phase.value)
        assertEquals(1, gate.pending, "the apply is on its way")

        o.session.close() // what a guard's Discard does: requestClose would have been ignored

        val toast = o.feedback.events.single()
        assertEquals("warning", toast.kind)
        assertEquals("Closed while applying documents to $context", toast.title)
        assertEquals("Documents sent before closing may have been applied; the rest were not. Nothing was rolled back.", toast.detail)
        assertEquals(listOf(9L), o.closed)
        gate.release()
        runCurrent()
        o.session.close()
        assertEquals(1, o.feedback.events.size, "the cancelled apply adds no toast and a second close none either")
        assertEquals(listOf(9L), o.closed)
    }

    @Test
    fun `closing in any other phase raises no toast`() = runTest {
        expectDryRunsPass("cm-a")
        expectApplySucceeds("cm-a")
        val editing = open(configMap("cm-a"))
        editing.session.close()
        assertTrue(editing.feedback.events.isEmpty())

        val reviewed = open(configMap("cm-a"))
        review(reviewed)
        assertTrue(reviewed.session.canApply.value)
        reviewed.session.close()
        assertTrue(reviewed.feedback.events.isEmpty())

        val done = open(configMap("cm-a"))
        review(done)
        confirm(done)
        assertIs<ApplyPhase.Done>(done.session.phase.value)
        val toasts = done.feedback.events.size
        done.session.close()
        assertEquals(toasts, done.feedback.events.size, "only the result toast, none for the close")
        assertEquals(listOf(9L), done.closed)
    }

    @Test
    fun `close is idempotent, cancels the session's work and not the injected scope`() = runTest {
        expectDryRunsPass("cm-a")
        val gate = GatedIo(testScheduler)
        val o = open(configMap("cm-a"), io = gate)
        gate.holdAfter(1)
        review(o)
        assertTrue(o.reviewing.running)

        o.session.close()
        o.session.close()
        gate.release()
        runCurrent()

        assertEquals(listOf(9L), o.closed)
        assertTrue(backgroundScope.isActive)
        val rows = o.rows
        assertTrue(rows.all { it.status == DocStatus.Pending }, "the cancelled review wrote nothing after the close")
        o.session.review() // a closed session ignores commands
        o.buffer.type("x")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(rows, o.rows)
        assertFalse(o.session.dirty.value, "a closed session no longer follows the buffer")
    }

    @Test
    fun `the titles and labels name the context`() = runTest {
        val o = open("")

        o.session.requestFocus()
        o.session.requestFocus()

        assertEquals("Apply YAML — $context", o.session.title)
        assertEquals("Apply YAML ($context)", o.session.dirtyLabel)
        assertEquals(2, o.session.focusRequests.value)
        assertEquals(SessionId("tab-1"), o.session.clusterSessionId)
    }

    // ── Secrets ────────────────────────────────────────────────────────────────

    private val sentinel = "hunter2-sentinel-value"
    private val sentinelBase64 = Base64.getEncoder().encodeToString(sentinel.toByteArray())
    private val secretPath = cmPath("demo-secret").replace("configmaps", "secrets")

    private fun secretText() = "apiVersion: v1\nkind: Secret\nmetadata:\n  name: demo-secret\n  namespace: $TEST_NAMESPACE\ndata:\n  password: $sentinelBase64\n"

    private fun secretRejection() = failure(422, "Secret \"demo-secret\" is invalid: data[password]: Invalid value: \"$sentinelBase64\" (decoded $sentinel)")

    @Test
    fun `a Secret document's dry-run message is scrubbed while masking is on`() = runTest {
        mock.server.expect().patch().withPath(dryRunPath(secretPath)).andReturn(422, secretRejection()).once()
        val o = open(secretText(), masking = { true })

        review(o)

        val failed = assertIs<DocStatus.Failed>(o.statuses.single())
        assertTrue(SecretYamlMasking.PLACEHOLDER in failed.message, failed.message)
        assertFalse(sentinelBase64 in failed.message, failed.message)
        assertFalse(sentinel in failed.message, failed.message)
        assertTrue(failed.message.startsWith("Secret \"demo-secret\" is invalid"), "the rest of the message stays: ${failed.message}")
    }

    @Test
    fun `a Secret document's dry-run message is verbatim when masking is off`() = runTest {
        mock.server.expect().patch().withPath(dryRunPath(secretPath)).andReturn(422, secretRejection()).once()
        val o = open(secretText(), masking = { false })

        review(o)

        val failed = assertIs<DocStatus.Failed>(o.statuses.single())
        assertTrue(sentinelBase64 in failed.message, "negative control: without masking nothing is scrubbed")
        assertFalse(SecretYamlMasking.PLACEHOLDER in failed.message)
    }

    @Test
    fun `a Secret document's apply failure is scrubbed while masking is on, and another kind's is not`() = runTest {
        mock.server.expect().patch().withPath(dryRunPath(secretPath)).andReturn(200, """{"apiVersion":"v1","kind":"Secret","metadata":{"name":"demo-secret"}}""").always()
        mock.server.expect().patch().withPath(applyPath(secretPath)).andReturn(403, failure(403, "cannot patch; value was $sentinelBase64")).once()
        expectDryRunsPass("cm-a")
        mock.server.expect().patch().withPath(applyPath(cmPath("cm-a"))).andReturn(403, failure(403, "cannot patch; value was $sentinelBase64")).once()
        val o = open(docs(secretText(), configMap("cm-a")), masking = { true })
        review(o)
        assertTrue(o.session.canApply.value)

        confirm(o)

        val secret = assertIs<DocStatus.Failed>(o.done.rows[0].status)
        assertFalse(sentinelBase64 in secret.message, secret.message)
        assertTrue(SecretYamlMasking.PLACEHOLDER in secret.message)
        val other = assertIs<DocStatus.Failed>(o.done.rows[1].status)
        assertTrue("cannot patch; value was $sentinelBase64" in other.message.removePrefix("Permission denied (403): "), "only Secret documents are scrubbed")
    }

    // ── Logging ────────────────────────────────────────────────────────────────

    @Test
    fun `the session logs no YAML, no message and no secret, only its id and a class name`() = runTest {
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            // Positive control: the appender records, so silence below means something.
            LoggerFactory.getLogger(ApplyYamlSession::class.java).info("probe-event")
            assertTrue(appender.list.any { it.formattedMessage == "probe-event" })

            mock.server.expect().patch().withPath(dryRunPath(secretPath)).andReturn(200, """{"apiVersion":"v1","kind":"Secret","metadata":{"name":"demo-secret"}}""").always()
            mock.server.expect().patch().withPath(applyPath(secretPath)).andReturn(200, """{"apiVersion":"v1","kind":"Secret","metadata":{"name":"demo-secret"}}""").once()
            val feedback = object : ActionFeedback {
                override fun success(title: String, detail: String?, undo: UndoAction?): Long = error(sentinel)

                override fun failure(title: String, detail: String?): Long = 0

                override fun warning(title: String, detail: String?): Long = 0

                override fun info(title: String, detail: String?): Long = 0
            }
            val buffer = StringEditorBuffer(secretText())
            val closed = mutableListOf<Long>()
            val session = ApplyYamlSession(9, SessionId("tab-1"), context, TEST_NAMESPACE, realWriter, { emptyList() }, feedback, { true }, buffer, backgroundScope, StandardTestDispatcher(testScheduler), StandardTestDispatcher(testScheduler), { closed += it })
            session.review()
            runCurrent()
            session.confirmApply()
            runCurrent() // the toast throws: the handler logs the class name only
            session.close()

            val sessionEvents = appender.list.filter { it.loggerName == ApplyYamlSession::class.java.name && it.formattedMessage != "probe-event" }
            assertEquals(
                listOf("Apply session 9 opened", "Apply session 9 failed: IllegalStateException", "Apply session 9 closed"),
                sessionEvents.map { it.formattedMessage },
            )
            for (event in appender.list) {
                val texts = listOfNotNull(event.formattedMessage, event.message, event.throwableProxy?.message, event.argumentArray?.joinToString { it.toString() })
                for (text in texts) {
                    assertFalse(sentinel in text || sentinelBase64 in text, "a logged event carries the secret: $text")
                    assertFalse("kind: Secret" in text || "apiVersion" in text, "a logged event carries YAML: $text")
                }
            }
            assertTrue(sessionEvents.all { it.throwableProxy == null }, "the session never hands an exception to the logger")
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `the wire bytes of an apply are the document's JSON, with the namespace filled in`() = runTest {
        expectDryRunsPass("cm-a")
        expectApplySucceeds("cm-a")
        val o = open(configMap("cm-a", namespace = null))
        review(o)

        val requests = mock.recorded { confirm(o) }

        val body = Serialization.unmarshal(requests.last { it.method == "PATCH" }.body, Map::class.java)
        assertEquals("ConfigMap", body["kind"])
        assertEquals(TEST_NAMESPACE, (body["metadata"] as Map<*, *>)["namespace"])
        assertEquals("cm-a", (body["metadata"] as Map<*, *>)["name"])
    }
}
