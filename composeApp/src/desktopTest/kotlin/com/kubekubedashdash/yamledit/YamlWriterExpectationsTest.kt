package com.kubekubedashdash.yamledit

import io.fabric8.kubernetes.api.model.APIResourceBuilder
import io.fabric8.kubernetes.api.model.APIResourceListBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.utils.Serialization
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exact requests the write engine sends to a real cluster, pinned with fabric8 mock
 * expectations. An expectation matches the WHOLE path string, query and parameter order included
 * (`SimpleRequest.equals`): a dry-run PUT is `<path>?fieldManager=kubekubedashdash&dryRun=All`
 * and fabric8's own server-side-apply patch has the same query, in the same order. The
 * mock answers the requests an expectation covers and stores the rest, so each test also reads what
 * was recorded. Loopback only; no real user state.
 */
class YamlWriterExpectationsTest {

    private val context = "cluster-a"
    private lateinit var mock: WriterMock
    private lateinit var writer: YamlWriter

    @BeforeTest
    fun setUp() {
        mock = WriterMock(context)
        writer = YamlWriter(mock.manager)
    }

    @AfterTest
    fun tearDown() {
        mock.stop("YamlWriterExpectationsTest")
    }

    private val configMapPath = "/api/v1/namespaces/$TEST_NAMESPACE/configmaps/demo-cm"
    private val widgetPath = "/apis/example.com/v1/namespaces/$TEST_NAMESPACE/widgets/w1"
    private val dryRunPutPath = "$configMapPath?fieldManager=kubekubedashdash&dryRun=All"

    /** fabric8 appends the dry-run flag after the field manager (`URLUtils.join` moves the older query parameters last). */
    private val dryRunPatchPath = "$widgetPath?fieldManager=kubekubedashdash&dryRun=All"

    private fun failure(code: Int, reason: String, message: String): Status = StatusBuilder().withCode(code).withStatus("Failure").withReason(reason).withMessage(message).build()

    private fun configMapBody(): LinkedHashMap<String, Any?> = configMapManifest().also { it.sub("metadata")["resourceVersion"] = "7" }

    private fun widgetJson(size: Int, resourceVersion: String = "7"): String = """{"apiVersion":"example.com/v1","kind":"Widget","metadata":{"name":"w1","namespace":"$TEST_NAMESPACE","resourceVersion":"$resourceVersion","generation":1,"uid":"00000000-0000-0000-0000-000000000001"},"spec":{"size":$size},"status":{"phase":"Ready"}}"""

    private fun widgetManifest(size: Int): LinkedHashMap<String, Any?> = linkedMapOf(
        "apiVersion" to "example.com/v1",
        "kind" to "Widget",
        "metadata" to linkedMapOf<String, Any?>("name" to "w1", "namespace" to TEST_NAMESPACE),
        "spec" to linkedMapOf<String, Any?>("size" to size),
    )

    private fun widgetDocument(size: Int) = applyDocument(widgetTarget(), widgetManifest(size))

    // ── slice A: the dry-run PUT ──────────────────────────────────────────────

    @Test
    fun `the dry run is a PUT to the exact path with both parameters and a JSON body`() {
        val body = configMapBody()
        mock.server.expect().put().withPath(dryRunPutPath).andReturn(200, """{"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"demo-cm","resourceVersion":"8"},"data":{"a":"1"}}""").once()

        var outcome: DryRunOutcome? = null
        val requests = mock.recorded { outcome = writer.dryRunReplace(configMapTarget(), context, body) }

        val request = requests.single()
        assertEquals("PUT", request.method)
        assertEquals(dryRunPutPath, request.path)
        assertEquals("application/json", request.contentType?.substringBefore(';'))
        assertEquals(Serialization.asJson(body), request.body, "the wire string is the one the guard checked")
        val dryRun = assertNotNull(outcome)
        assertFalse(dryRun.simulatedLocally)
        assertEquals("8", (assertNotNull(dryRun.result)["metadata"] as Map<*, *>)["resourceVersion"])
    }

    @Test
    fun `a 422 is Invalid and carries the server's message verbatim`() {
        val message = "ConfigMap \"demo-cm\" is invalid: data[a]: Invalid value: \"x\""
        mock.server.expect().put().withPath(dryRunPutPath).andReturn(422, failure(422, "Invalid", message)).once()

        val thrown = assertFailsWith<YamlWriteException> { writer.dryRunReplace(configMapTarget(), context, configMapBody()) }

        assertEquals(WriteErrorKind.Invalid, thrown.kind)
        assertEquals(422, thrown.code)
        assertEquals(message, thrown.message)
        assertNull(thrown.cause)
    }

    @Test
    fun `a 403 is Forbidden with the permission prefix`() {
        val message = "configmaps \"demo-cm\" is forbidden: User \"dev@example.com\" cannot update resource \"configmaps\""
        mock.server.expect().put().withPath(dryRunPutPath).andReturn(403, failure(403, "Forbidden", message)).once()

        val thrown = assertFailsWith<YamlWriteException> { writer.dryRunReplace(configMapTarget(), context, configMapBody()) }

        assertEquals(WriteErrorKind.Forbidden, thrown.kind)
        assertEquals(403, thrown.code)
        assertEquals("Permission denied (403): $message", thrown.message)
    }

    @Test
    fun `the status code decides the kind, never the reason`() {
        // The demo mock says reason "Invalid" for everything; a real server says what it means.
        mock.server.expect().put().withPath(dryRunPutPath).andReturn(409, failure(409, "Invalid", "the object has been modified")).once()
        mock.server.expect().put().withPath(dryRunPutPath).andReturn(400, failure(400, "Conflict", "bad request body")).once()
        // 415 is what the demo mock answers a server-side apply; no 5xx here: fabric8 retries those.
        mock.server.expect().put().withPath(dryRunPutPath).andReturn(415, failure(415, "Invalid", "unsupported media type")).once()

        val kinds = List(3) { assertFailsWith<YamlWriteException> { writer.dryRunReplace(configMapTarget(), context, configMapBody()) } }

        assertEquals(listOf(WriteErrorKind.Conflict, WriteErrorKind.BadRequest, WriteErrorKind.Other), kinds.map { it.kind })
        assertEquals(listOf(409, 400, 415), kinds.map { it.code })
    }

    @Test
    fun `the real replace is a PUT without dryRun and returns the new object`() {
        val path = "$configMapPath?fieldManager=kubekubedashdash"
        mock.server.expect().put().withPath(path).andReturn(200, """{"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"demo-cm","resourceVersion":"8"},"data":{"a":"1"}}""").once()

        var live: LiveObject? = null
        val requests = mock.recorded { live = writer.replace(configMapTarget(), context, configMapBody()) }

        assertEquals("PUT", requests.single().method)
        assertEquals(path, requests.single().path)
        assertEquals("8", live!!.resourceVersion)
    }

    @Test
    fun `an answer that is not a JSON object is Other without the parser's message`() {
        mock.server.expect().put().withPath(dryRunPutPath).andReturn(200, "[1, 2, 3]").once()

        val thrown = assertFailsWith<YamlWriteException> { writer.dryRunReplace(configMapTarget(), context, configMapBody()) }

        assertEquals(WriteErrorKind.Other, thrown.kind)
        assertTrue(thrown.message.startsWith("Request failed ("), "got: ${thrown.message}")
        assertFalse("1, 2, 3" in thrown.message, "the payload is not echoed")
    }

    @Test
    fun `an object without a resourceVersion is refused by fetch`() {
        mock.server.expect().get().withPath(configMapPath).andReturn(200, """{"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"demo-cm"}}""").once()

        val thrown = assertFailsWith<YamlWriteException> { writer.fetch(configMapTarget(), context) }

        assertEquals(WriteErrorKind.Other, thrown.kind)
        assertEquals("The server returned no resourceVersion.", thrown.message)
    }

    // ── slice B: server-side apply on a real cluster ──────────────────────────

    private fun gkrOf(body: Map<String, Any?>): GenericKubernetesResource = Serialization.unmarshal(Serialization.asJson(body), GenericKubernetesResource::class.java)

    @Test
    fun `the apply dry run is a server-side-apply PATCH with dryRun and the field manager and no force`() {
        val document = widgetDocument(size = 2)
        val patchPath = dryRunPatchPath
        mock.server.expect().get().withPath(widgetPath).andReturn(200, widgetJson(size = 1)).once()
        mock.server.expect().patch().withPath(patchPath).andReturn(200, widgetJson(size = 2, resourceVersion = "7")).once()

        var outcome: ApplyOutcome? = null
        val requests = mock.recorded { outcome = writer.applyDryRun(document, context) }

        assertEquals(ApplyOutcome.Configured, outcome)
        assertEquals(listOf("GET", "PATCH"), requests.map { it.method })
        val patch = requests.last()
        assertEquals(patchPath, patch.path)
        assertEquals("application/apply-patch+yaml", patch.contentType?.substringBefore(';'))
        assertTrue("dryRun=All" in patch.path)
        assertTrue("fieldManager=kubekubedashdash" in patch.path)
        assertFalse("force=" in patch.path, "never force: a conflict with another manager is shown, not overridden")
        assertEquals(Serialization.asJson(gkrOf(document.body)), patch.body, "the wire string is asJson of the guarded resource")
    }

    @Test
    fun `the apply dry run reads Created, Unchanged and Configured from the current object`() {
        val patchPath = dryRunPatchPath

        // Absent: the GET answers 404 (the one-argument raw turns it into "absent").
        mock.server.expect().patch().withPath(patchPath).andReturn(200, widgetJson(size = 2)).once()
        assertEquals(ApplyOutcome.Created, writer.applyDryRun(widgetDocument(2), context))

        // Present and identical apart from server bookkeeping: the result differs in resourceVersion/generation only.
        mock.server.expect().get().withPath(widgetPath).andReturn(200, widgetJson(size = 2, resourceVersion = "7")).once()
        mock.server.expect().patch().withPath(patchPath).andReturn(200, widgetJson(size = 2, resourceVersion = "9")).once()
        assertEquals(ApplyOutcome.Unchanged, writer.applyDryRun(widgetDocument(2), context))

        // Present with another spec.
        mock.server.expect().get().withPath(widgetPath).andReturn(200, widgetJson(size = 1)).once()
        mock.server.expect().patch().withPath(patchPath).andReturn(200, widgetJson(size = 2)).once()
        assertEquals(ApplyOutcome.Configured, writer.applyDryRun(widgetDocument(2), context))
    }

    @Test
    fun `the real apply is the same PATCH without dryRun`() {
        val patchPath = "$widgetPath?fieldManager=kubekubedashdash"
        mock.server.expect().get().withPath(widgetPath).andReturn(200, widgetJson(size = 1)).once()
        mock.server.expect().patch().withPath(patchPath).andReturn(200, widgetJson(size = 2, resourceVersion = "8")).once()

        var outcome: ApplyOutcome? = null
        val requests = mock.recorded { outcome = writer.apply(widgetDocument(2), context) }

        assertEquals(ApplyOutcome.Configured, outcome)
        assertEquals(listOf("GET", "PATCH"), requests.map { it.method })
        assertEquals(patchPath, requests.last().path)
        assertTrue(requests.none { "dryRun" in it.path })
    }

    @Test
    fun `a cluster-scoped document is applied without a namespace`() {
        val target = EditTarget("Gadget", "example.com", "v1", "gadgets", false, "g1", null)
        val body = linkedMapOf<String, Any?>("apiVersion" to "example.com/v1", "kind" to "Gadget", "metadata" to linkedMapOf<String, Any?>("name" to "g1"))
        val path = "/apis/example.com/v1/gadgets/g1"
        mock.server.expect().patch().withPath("$path?fieldManager=kubekubedashdash&dryRun=All").andReturn(200, """{"apiVersion":"example.com/v1","kind":"Gadget","metadata":{"name":"g1"}}""").once()

        val outcome = writer.applyDryRun(applyDocument(target, body), context)

        assertEquals(ApplyOutcome.Created, outcome)
    }

    @Test
    fun `a 409 from server-side apply is a Conflict`() {
        val patchPath = dryRunPatchPath
        mock.server.expect().get().withPath(widgetPath).andReturn(200, widgetJson(size = 1)).once()
        mock.server.expect().patch().withPath(patchPath).andReturn(409, failure(409, "Conflict", "Apply failed with 1 conflict: conflict with \"controller\": .spec.size")).once()

        val thrown = assertFailsWith<YamlWriteException> { writer.applyDryRun(widgetDocument(2), context) }

        assertEquals(WriteErrorKind.Conflict, thrown.kind)
        assertEquals(409, thrown.code)
    }

    @Test
    fun `a 403 from the current-object GET is Forbidden`() {
        mock.server.expect().get().withPath(widgetPath).andReturn(403, failure(403, "Forbidden", "widgets \"w1\" is forbidden")).once()

        val thrown = assertFailsWith<YamlWriteException> { writer.applyDryRun(widgetDocument(2), context) }

        assertEquals(WriteErrorKind.Forbidden, thrown.kind)
    }

    // ── discover ──────────────────────────────────────────────────────────────

    private fun apiResources(groupVersion: String, vararg entries: Triple<String, String, Boolean>) = APIResourceListBuilder()
        .withGroupVersion(groupVersion)
        .withResources(entries.map { (name, kind, namespaced) -> APIResourceBuilder().withName(name).withKind(kind).withNamespaced(namespaced).build() })
        .build()

    @Test
    fun `discover reads the APIResourceList and skips sub-resources`() {
        mock.server.expect().get().withPath("/apis/example.com/v1").andReturn(
            200,
            apiResources(
                "example.com/v1",
                Triple("widgets/status", "Widget", true),
                Triple("widgets", "Widget", true),
                Triple("gadgets", "Gadget", false),
            ),
        ).always()

        assertEquals(ResolvedKind("example.com", "v1", "Widget", "widgets", true), writer.discover("example.com/v1", "Widget", context))
        assertEquals(ResolvedKind("example.com", "v1", "Gadget", "gadgets", false), writer.discover("example.com/v1", "Gadget", context))
        assertNull(writer.discover("example.com/v1", "Sprocket", context), "a kind the cluster does not list")
    }

    @Test
    fun `discover of the core group asks for api v1`() {
        mock.server.expect().get().withPath("/api/v1").andReturn(200, apiResources("v1", Triple("configmaps", "ConfigMap", true))).once()

        val resolved = writer.discover("v1", "ConfigMap", context)

        assertEquals(ResolvedKind("", "v1", "ConfigMap", "configmaps", true), resolved)
    }

    @Test
    fun `discover answers null for a 404, a 403 and an unusable apiVersion`() {
        mock.server.expect().get().withPath("/apis/gone.example.com/v1").andReturn(404, failure(404, "NotFound", "the server could not find the requested resource")).once()
        mock.server.expect().get().withPath("/apis/secret.example.com/v1").andReturn(403, failure(403, "Forbidden", "forbidden")).once()

        assertNull(writer.discover("gone.example.com/v1", "Widget", context))
        assertNull(writer.discover("secret.example.com/v1", "Widget", context))
        val requests = mock.recorded { assertNull(writer.discover("../../version", "Widget", context)) }
        assertTrue(requests.isEmpty(), "an apiVersion that is not group/version is not even asked: ${requests.map { it.path }}")
    }

    @Test
    fun `discover never asks for an apiVersion with a dot segment`() {
        val requests = mock.recorded {
            for (apiVersion in listOf("apps/..", "../v1", "..", "./v1", "apps/.", "apps/v1/..")) {
                assertNull(writer.discover(apiVersion, "Deployment", context), apiVersion)
            }
        }

        assertTrue(requests.isEmpty(), "a dot segment would leave the discovery path: ${requests.map { it.path }}")
    }

    @Test
    fun `a server error during discovery is a YamlWriteException, not null`() {
        mock.server.expect().get().withPath("/apis/broken.example.com/v1").andReturn(405, failure(405, "MethodNotAllowed", "no discovery here")).once()

        val thrown = assertFailsWith<YamlWriteException> { writer.discover("broken.example.com/v1", "Widget", context) }

        assertEquals(WriteErrorKind.Other, thrown.kind)
        assertEquals(405, thrown.code)
    }
}
