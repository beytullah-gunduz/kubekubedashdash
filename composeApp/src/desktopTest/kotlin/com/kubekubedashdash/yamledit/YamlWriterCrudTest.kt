package com.kubekubedashdash.yamledit

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.SecretYamlMasking
import io.fabric8.kubernetes.client.utils.Serialization
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write engine against the fabric8 CRUD mock the demo cluster runs on: what a read, a replace
 * and the demo fallback of an apply do on a store that behaves like the real API for resourceVersion
 * conflicts. The mock persists a `dryRun=All` request and has no server-side apply, so the tests
 * also pin that nothing of the kind is ever sent to it. Loopback only; no real user state.
 */
class YamlWriterCrudTest {

    private val context = "cluster-a"
    private lateinit var mock: WriterMock
    private lateinit var writer: YamlWriter

    /** A writer that treats every context as the demo cluster, whatever the manager says. */
    private lateinit var demoWriter: YamlWriter

    @BeforeTest
    fun setUp() {
        mock = WriterMock(context)
        writer = YamlWriter(mock.manager)
        demoWriter = YamlWriter(mock.manager, demoMode = { true })
    }

    @AfterTest
    fun tearDown() {
        mock.stop("YamlWriterCrudTest")
    }

    private val masked = SecretYamlMasking.PLACEHOLDER

    /** The body the editor would send after changing data `a` of the live ConfigMap to [value]. */
    private fun editedBody(base: LiveObject, value: String): LinkedHashMap<String, Any?> {
        val edited = EditProjection.visible(base.json)
        edited.sub("data")["a"] = value
        return EditProjection.body(edited, base.json)
    }

    // ── fetch / replace ───────────────────────────────────────────────────────

    @Test
    fun `fetch returns the whole object and its resourceVersion`() {
        mock.seedConfigMap("demo-cm", mapOf("a" to "1"), withManagedFields = true)

        val live = writer.fetch(configMapTarget(), context)

        assertEquals("ConfigMap", live.json["kind"])
        assertEquals(mapOf("a" to "1"), live.json["data"])
        assertTrue(live.resourceVersion.isNotBlank())
        assertEquals(live.json.sub("metadata")["resourceVersion"], live.resourceVersion)
        assertNotNull(live.json.sub("metadata")["managedFields"], "fetch returns the full object; hiding managedFields is the projection's job")
    }

    @Test
    fun `fetch of a missing object is NotFound with code 404 and a fixed message`() {
        val thrown = assertFailsWith<YamlWriteException> { writer.fetch(configMapTarget("ghost"), context) }

        assertEquals(WriteErrorKind.NotFound, thrown.kind)
        assertEquals(404, thrown.code)
        assertEquals("ConfigMap $TEST_NAMESPACE/ghost was not found on the cluster.", thrown.message)
    }

    @Test
    fun `a cluster-scoped target is fetched without a namespace`() {
        mock.seed.nodes().resource(io.fabric8.kubernetes.api.model.NodeBuilder().withNewMetadata().withName("node-1").endMetadata().build()).create()

        val live = writer.fetch(EditTarget("Node", "", "v1", "nodes", false, "node-1", null), context)

        assertEquals("Node", live.json["kind"])
    }

    @Test
    fun `replace with the base resourceVersion succeeds and returns the new resourceVersion`() {
        mock.seedConfigMap()
        val base = writer.fetch(configMapTarget(), context)

        val replaced = writer.replace(configMapTarget(), context, editedBody(base, "2"))

        assertNotEquals(base.resourceVersion, replaced.resourceVersion, "the write bumped the resourceVersion")
        assertEquals(mapOf("a" to "2"), replaced.json["data"])
        assertEquals(mapOf("a" to "2"), writer.fetch(configMapTarget(), context).json["data"])
    }

    @Test
    fun `replace with a stale resourceVersion is a Conflict and leaves the object unchanged`() {
        mock.seedConfigMap()
        val stale = writer.fetch(configMapTarget(), context)
        writer.replace(configMapTarget(), context, editedBody(stale, "2")) // someone else wrote first

        val thrown = assertFailsWith<YamlWriteException> { writer.replace(configMapTarget(), context, editedBody(stale, "3")) }

        assertEquals(WriteErrorKind.Conflict, thrown.kind)
        assertEquals(409, thrown.code)
        assertEquals(mapOf("a" to "2"), writer.fetch(configMapTarget(), context).json["data"], "the stale write did not land")
    }

    @Test
    fun `replace refuses a body without a resourceVersion before sending anything`() {
        mock.seedConfigMap()
        val body = configMapManifest()

        val requests = mock.recorded { assertFailsWith<IllegalArgumentException> { writer.replace(configMapTarget(), context, body) } }

        assertTrue(requests.isEmpty(), "an unconditional PUT would overwrite; got $requests")
    }

    @Test
    fun `a replaced custom resource keeps the status the editor hid`() {
        mock.seedWidget(spec = mapOf("size" to 1), status = mapOf("phase" to "Ready"))
        val base = writer.fetch(widgetTarget(), context)
        assertEquals(mapOf("phase" to "Ready"), base.json["status"])
        val edited = EditProjection.visible(base.json)
        assertFalse(edited.containsKey("status"), "the editor never shows status")
        edited.sub("spec")["size"] = 5

        writer.replace(widgetTarget(), context, EditProjection.body(edited, base.json))

        val after = writer.fetch(widgetTarget(), context)
        assertEquals(mapOf("size" to 5), after.json["spec"])
        assertEquals(mapOf("phase" to "Ready"), after.json["status"], "the mock wipes a status the PUT omits, so the body must carry it")
    }

    @Test
    fun `the PUT carries the field manager in its path and never managedFields in its body`() {
        mock.seedConfigMap(withManagedFields = true)
        val base = writer.fetch(configMapTarget(), context)
        assertNotNull(base.json.sub("metadata")["managedFields"], "the fixture has managedFields, so their absence below means something")

        val requests = mock.recorded { writer.replace(configMapTarget(), context, editedBody(base, "2")) }

        val put = requests.single { it.method == "PUT" }
        assertEquals("/api/v1/namespaces/$TEST_NAMESPACE/configmaps/demo-cm?fieldManager=kubekubedashdash", put.path)
        assertFalse("managedFields" in put.body, "managedFields are never sent")
        assertEquals("application/json", put.contentType?.substringBefore(';'))
        val sentMetadata = Serialization.unmarshal(put.body, Map::class.java)["metadata"] as Map<*, *>
        assertEquals(base.resourceVersion, sentMetadata["resourceVersion"], "the base resourceVersion is the precondition")
    }

    // ── demo mode: nothing that the mock would persist is ever sent ──────────

    @Test
    fun `the demo dry run records zero requests and says it was simulated`() {
        mock.seedConfigMap()
        val base = writer.fetch(configMapTarget(), context)
        val body = editedBody(base, "2")

        var outcome: DryRunOutcome? = null
        val requests = mock.recorded { outcome = demoWriter.dryRunReplace(configMapTarget(), context, body) }

        assertTrue(requests.isEmpty(), "a dryRun request would be persisted by the mock; got $requests")
        assertEquals(DryRunOutcome(null, simulatedLocally = true), outcome)
        assertEquals(mapOf("a" to "1"), writer.fetch(configMapTarget(), context).json["data"], "nothing was written")
    }

    @Test
    fun `the demo apply dry run sends only GETs`() {
        mock.seedConfigMap()
        val present = applyDocument(configMapTarget(), configMapManifest(data = mapOf("a" to "9")))
        val absent = applyDocument(configMapTarget("new-cm"), configMapManifest("new-cm"))

        val requests = mock.recorded {
            assertEquals(ApplyOutcome.Configured, demoWriter.applyDryRun(present, context))
            assertEquals(ApplyOutcome.Created, demoWriter.applyDryRun(absent, context))
        }

        assertEquals(listOf("GET", "GET"), requests.map { it.method })
        assertTrue(requests.none { "dryRun" in it.path }, "no dryRun parameter in any path: ${requests.map { it.path }}")
        assertEquals(mapOf("a" to "1"), writer.fetch(configMapTarget(), context).json["data"], "nothing was written")
    }

    @Test
    fun `the default demo rule follows the context label, not only the manager's connection`() {
        val demoManager = KubeConnectionManager()
        try {
            demoManager.connectWithClient(mock.server.createClient(), DemoContext.MOCK_CONTEXT_NAME + " #1").getOrThrow()
            val demoLabelled = YamlWriter(demoManager)

            assertTrue(demoLabelled.isDemo(DemoContext.MOCK_CONTEXT_NAME + " #1"))
            assertFalse(writer.isDemo("cluster-a"), "a plain context is not a demo")
            assertTrue(writer.isDemo(DemoContext.MOCK_CONTEXT_NAME), "the label rule holds for any manager")
            mock.seedConfigMap()
            val requests = mock.recorded {
                val outcome = demoLabelled.dryRunReplace(configMapTarget(), DemoContext.MOCK_CONTEXT_NAME + " #1", configMapBodyWithVersion("1"))
                assertTrue(outcome.simulatedLocally)
            }
            assertTrue(requests.isEmpty(), "got $requests")
        } finally {
            demoManager.close()
        }
    }

    private fun configMapBodyWithVersion(resourceVersion: String): LinkedHashMap<String, Any?> = configMapManifest().also { it.sub("metadata")["resourceVersion"] = resourceVersion }

    // ── refusals that must cost zero requests ─────────────────────────────────

    private class WriteCall(val name: String, val run: () -> Unit)

    private fun writeCalls(w: YamlWriter, target: EditTarget, ctx: String, body: LinkedHashMap<String, Any?>, document: ApplyDocument) = listOf(
        WriteCall("dryRunReplace") { w.dryRunReplace(target, ctx, body) },
        WriteCall("replace") { w.replace(target, ctx, body) },
        WriteCall("applyDryRun") { w.applyDryRun(document, ctx) },
        WriteCall("apply") { w.apply(document, ctx) },
    )

    @Test
    fun `a body that carries a mask token is refused by every write method with zero requests`() {
        mock.seedConfigMap()
        val body = configMapBodyWithVersion("1").also { it.sub("data")["a"] = masked }
        val document = applyDocument(configMapTarget(), configMapManifest(data = mapOf("a" to masked)))

        for ((mode, w) in listOf("real" to writer, "demo" to demoWriter)) {
            for (call in writeCalls(w, configMapTarget(), context, body, document)) {
                val requests = mock.recorded {
                    val thrown = assertFailsWith<YamlWriteException>("$mode ${call.name}") { call.run() }
                    assertEquals(WriteErrorKind.MaskedValue, thrown.kind, "$mode ${call.name}")
                }
                assertTrue(requests.isEmpty(), "$mode ${call.name} must not reach the server; got ${requests.map { it.method + " " + it.path }}")
            }
        }
    }

    @Test
    fun `a binary or multiline token is refused too`() {
        mock.seedConfigMap()
        for (token in listOf("<binary: 12 bytes>", "<multiline: 3 bytes>")) {
            val body = configMapBodyWithVersion("1").also { it.sub("data")["a"] = "x $token" }
            val requests = mock.recorded {
                assertEquals(WriteErrorKind.MaskedValue, assertFailsWith<YamlWriteException> { writer.replace(configMapTarget(), context, body) }.kind)
            }
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `a context the manager no longer shows is WrongCluster with zero requests for every method`() {
        mock.seedConfigMap()
        val body = configMapBodyWithVersion("1")
        val document = applyDocument(configMapTarget(), configMapManifest())
        val other = "cluster-b"
        val calls = writeCalls(writer, configMapTarget(), other, body, document) +
            WriteCall("fetch") { writer.fetch(configMapTarget(), other) } +
            WriteCall("discover") { writer.discover("v1", "ConfigMap", other) }

        for (call in calls) {
            val requests = mock.recorded {
                val thrown = assertFailsWith<YamlWriteException>(call.name) { call.run() }
                assertEquals(WriteErrorKind.WrongCluster, thrown.kind, call.name)
                assertContains(thrown.message, other)
            }
            assertTrue(requests.isEmpty(), "${call.name} must not reach the server; got $requests")
        }
    }

    @Test
    fun `a closed manager is WrongCluster too`() {
        mock.seedConfigMap()
        mock.manager.close()

        assertEquals(WriteErrorKind.WrongCluster, assertFailsWith<YamlWriteException> { writer.fetch(configMapTarget(), context) }.kind)
    }

    // ── slice B on the demo cluster: GET + POST / merge + PUT ─────────────────

    @Test
    fun `the demo apply creates an absent object with a POST`() {
        val document = applyDocument(configMapTarget("new-cm"), configMapManifest("new-cm", mapOf("k" to "v")))

        var outcome: ApplyOutcome? = null
        val requests = mock.recorded { outcome = demoWriter.apply(document, context) }

        assertEquals(ApplyOutcome.Created, outcome)
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
        assertEquals("/api/v1/namespaces/$TEST_NAMESPACE/configmaps?fieldManager=kubekubedashdash", requests.last().path)
        assertEquals(mapOf("k" to "v"), writer.fetch(configMapTarget("new-cm"), context).json["data"])
    }

    @Test
    fun `the demo apply merges into a present object and PUTs with its current resourceVersion`() {
        mock.seedConfigMap("demo-cm", mapOf("a" to "1", "b" to "2"))
        val before = writer.fetch(configMapTarget(), context)
        val document = applyDocument(configMapTarget(), configMapManifest(data = mapOf("b" to "3", "c" to "4")))

        var outcome: ApplyOutcome? = null
        val requests = mock.recorded { outcome = demoWriter.apply(document, context) }

        assertEquals(ApplyOutcome.Configured, outcome)
        assertEquals(listOf("GET", "PUT"), requests.map { it.method })
        val put = requests.last()
        assertEquals("/api/v1/namespaces/$TEST_NAMESPACE/configmaps/demo-cm?fieldManager=kubekubedashdash", put.path)
        assertEquals(before.resourceVersion, Serialization.unmarshal(put.body, Map::class.java).let { (it["metadata"] as Map<*, *>)["resourceVersion"] }, "the current resourceVersion is the precondition")
        val after = writer.fetch(configMapTarget(), context)
        assertEquals(mapOf("a" to "1", "b" to "3", "c" to "4"), after.json["data"], "maps merge, scalars from the document win")
        assertEquals(mapOf("app" to "demo"), after.json.sub("metadata")["labels"], "fields the document does not mention stay")
        assertNotEquals(before.resourceVersion, after.resourceVersion)
    }

    @Test
    fun `the demo apply keeps the status of a custom resource`() {
        mock.seedWidget(spec = mapOf("size" to 1, "color" to "red"), status = mapOf("phase" to "Ready"))
        val manifest = linkedMapOf<String, Any?>(
            "apiVersion" to "example.com/v1",
            "kind" to "Widget",
            "metadata" to linkedMapOf<String, Any?>("name" to "w1", "namespace" to TEST_NAMESPACE),
            "spec" to linkedMapOf<String, Any?>("size" to 3),
        )

        assertEquals(ApplyOutcome.Configured, demoWriter.apply(applyDocument(widgetTarget(), manifest), context))

        val after = writer.fetch(widgetTarget(), context)
        assertEquals(mapOf("size" to 3, "color" to "red"), after.json["spec"])
        assertEquals(mapOf("phase" to "Ready"), after.json["status"], "the PUT re-attaches the current status")
    }

    @Test
    fun `the demo apply of an identical document is Unchanged and sends no write`() {
        mock.seedConfigMap("demo-cm", mapOf("a" to "1"))
        val before = writer.fetch(configMapTarget(), context)
        val document = applyDocument(configMapTarget(), configMapManifest(data = mapOf("a" to "1")))

        var dry: ApplyOutcome? = null
        var applied: ApplyOutcome? = null
        val requests = mock.recorded {
            dry = demoWriter.applyDryRun(document, context)
            applied = demoWriter.apply(document, context)
        }

        assertEquals(ApplyOutcome.Unchanged, dry)
        assertEquals(ApplyOutcome.Unchanged, applied)
        assertTrue(requests.all { it.method == "GET" }, "got ${requests.map { it.method }}")
        assertEquals(before.resourceVersion, writer.fetch(configMapTarget(), context).resourceVersion, "no write, no new resourceVersion")
    }

    @Test
    fun `the demo merge PUT is guarded like every other wire string`() {
        // The document is clean; the CURRENT object holds a token, so the merged PUT body would carry it.
        mock.seedConfigMap("demo-cm", mapOf("a" to masked))
        val document = applyDocument(configMapTarget(), configMapManifest(data = mapOf("b" to "2")))

        val requests = mock.recorded {
            val thrown = assertFailsWith<YamlWriteException> { demoWriter.apply(document, context) }
            assertEquals(WriteErrorKind.MaskedValue, thrown.kind)
        }

        assertEquals(listOf("GET"), requests.map { it.method }, "the PUT never left")
    }

    @Test
    fun `a failing demo write reports the server's code`() {
        mock.seedConfigMap()
        val document = applyDocument(configMapTarget("new-cm"), configMapManifest("new-cm"))
        mock.seedConfigMap("new-cm") // appears between the GET and the POST in a real race; here it makes the POST a 409
        // Hide the object from the GET so the writer takes the create path.
        mock.server.expect().get().withPath("/api/v1/namespaces/$TEST_NAMESPACE/configmaps/new-cm").andReturn(404, "").once()

        val thrown = assertFailsWith<YamlWriteException> { demoWriter.apply(document, context) }

        assertEquals(WriteErrorKind.Conflict, thrown.kind)
        assertEquals(409, thrown.code)
        assertNull(thrown.cause, "no cause: its message would carry the server's text into a log")
    }
}
