package com.kubekubedashdash.yamledit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class EditProjectionTest {

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

    /** The nested map under [key], for the test to change in place. */
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.sub(key: String): MutableMap<String, Any?> = this[key] as MutableMap<String, Any?>

    private fun deployment(): LinkedHashMap<String, Any?> = linkedMapOf(
        "apiVersion" to "apps/v1",
        "kind" to "Deployment",
        "metadata" to linkedMapOf<String, Any?>(
            "name" to "web",
            "namespace" to "example-ns",
            "uid" to "00000000-0000-0000-0000-000000000001",
            "resourceVersion" to "100",
            "generation" to 3L,
            "creationTimestamp" to "2026-01-01T00:00:00Z",
            "labels" to linkedMapOf("app" to "web"),
            "managedFields" to listOf(linkedMapOf("manager" to "kubectl", "operation" to "Update")),
        ),
        "spec" to linkedMapOf<String, Any?>("replicas" to 2, "template" to linkedMapOf("spec" to linkedMapOf("containers" to listOf(linkedMapOf("name" to "c"))))),
        "status" to linkedMapOf<String, Any?>("availableReplicas" to 2, "conditions" to listOf(linkedMapOf("type" to "Available"))),
    )

    private fun node(): LinkedHashMap<String, Any?> = linkedMapOf(
        "apiVersion" to "v1",
        "kind" to "Node",
        "metadata" to linkedMapOf<String, Any?>("name" to "node-1", "resourceVersion" to "7"),
        "spec" to linkedMapOf<String, Any?>("unschedulable" to false),
        "status" to linkedMapOf<String, Any?>("phase" to "Ready"),
    )

    private fun customResource(): LinkedHashMap<String, Any?> = linkedMapOf(
        "apiVersion" to "example.com/v1",
        "kind" to "Widget",
        "metadata" to linkedMapOf<String, Any?>(
            "name" to "w1",
            "namespace" to "example-ns",
            "resourceVersion" to "55",
            "generation" to 2L,
            "managedFields" to listOf(linkedMapOf("manager" to "controller")),
        ),
        "spec" to linkedMapOf<String, Any?>("size" to "large"),
        "status" to linkedMapOf<String, Any?>("state" to "Ready", "observed" to linkedMapOf("generation" to 2L)),
    )

    private val allowed = setOf("100", "90")

    /** The tree the editor would hold after the user changed nothing. */
    private fun untouched(base: Map<String, Any?> = deployment()) = EditProjection.visible(base)

    private fun messages(edited: Any?, base: Map<String, Any?> = deployment(), rvs: Set<String> = allowed) = EditProjection.validate(edited, base, rvs).map { it.message }

    // ── projections ──────────────────────────────────────────────────────────

    @Test
    fun `visible drops only status and managedFields`() {
        val full = deployment()
        val visible = EditProjection.visible(full)
        val expected = deployment()
        expected.remove("status")
        expected.sub("metadata").remove("managedFields")
        assertEquals(expected, visible)
        assertEquals(listOf("apiVersion", "kind", "metadata", "spec"), visible.keys.toList())
        assertEquals(
            listOf("name", "namespace", "uid", "resourceVersion", "generation", "creationTimestamp", "labels"),
            visible.map("metadata").keys.toList(),
        )
    }

    @Test
    fun `visible is a deep copy and leaves its input alone`() {
        val full = deployment()
        val snapshot = deployment()
        val visible = EditProjection.visible(full)
        assertEquals(snapshot, full)
        assertNotSame(full["spec"], visible["spec"])
        assertNotSame(full.map("metadata")["labels"], visible.map("metadata")["labels"])
        visible.sub("spec")["replicas"] = 99
        assertEquals(snapshot, full)
    }

    @Test
    fun `visible of an object without metadata or status is a plain copy`() {
        assertEquals(linkedMapOf<String, Any?>("kind" to "X"), EditProjection.visible(linkedMapOf("kind" to "X", "status" to "s")))
        assertEquals(linkedMapOf<String, Any?>("metadata" to "odd"), EditProjection.visible(linkedMapOf("metadata" to "odd")))
    }

    @Test
    fun `comparable additionally drops only resourceVersion and generation`() {
        val comparable = EditProjection.comparable(deployment())
        val expected = EditProjection.visible(deployment())
        expected.sub("metadata").remove("resourceVersion")
        expected.sub("metadata").remove("generation")
        assertEquals(expected, comparable)
        assertEquals(listOf("name", "namespace", "uid", "creationTimestamp", "labels"), comparable.map("metadata").keys.toList())
    }

    @Test
    fun `comparable ignores a status-only change and a version bump but sees a spec change`() {
        val before = deployment()
        val bumped = deployment()
        bumped.sub("metadata").apply {
            put("resourceVersion", "101")
            put("generation", 4L)
        }
        bumped.sub("status")["availableReplicas"] = 0
        assertEquals(EditProjection.comparable(before), EditProjection.comparable(bumped))
        bumped.sub("spec")["replicas"] = 3
        assertTrue(EditProjection.comparable(before) != EditProjection.comparable(bumped))
    }

    @Test
    fun `withoutServerFields drops the two fields and nothing else`() {
        val m = EditProjection.withoutServerFields(deployment())
        val metadata = m.map("metadata")
        assertFalse(metadata.containsKey("resourceVersion"))
        assertFalse(metadata.containsKey("generation"))
        assertTrue(metadata.containsKey("managedFields"))
        assertTrue(m.containsKey("status"))
        assertEquals(6, metadata.size)
    }

    @Test
    fun `alignServerFields takes resourceVersion and generation from the edited tree`() {
        val base = untouched()
        val edited = untouched()
        edited.sub("metadata").apply {
            put("resourceVersion", "90")
            put("generation", 2)
        }
        val aligned = EditProjection.alignServerFields(base, edited)
        assertEquals("90", aligned.map("metadata")["resourceVersion"])
        assertEquals(2, aligned.map("metadata")["generation"])
        // Nothing else moved, and the base was not touched.
        val expected = untouched()
        expected.sub("metadata").apply {
            put("resourceVersion", "90")
            put("generation", 2)
        }
        assertEquals(expected, aligned)
        assertEquals("100", base.map("metadata")["resourceVersion"])
        assertEquals(3L, base.map("metadata")["generation"])
    }

    @Test
    fun `alignServerFields removes a field the edited tree lacks`() {
        val edited = untouched()
        edited.sub("metadata").apply {
            remove("resourceVersion")
            remove("generation")
        }
        val aligned = EditProjection.alignServerFields(untouched(), edited)
        assertFalse(aligned.map("metadata").containsKey("resourceVersion"))
        assertFalse(aligned.map("metadata").containsKey("generation"))
        assertEquals(EditProjection.withoutServerFields(untouched()), aligned)
    }

    @Test
    fun `alignServerFields adds a field the base lacks and survives a missing metadata`() {
        val base = untouched()
        base.sub("metadata").remove("generation")
        val edited = untouched()
        val aligned = EditProjection.alignServerFields(base, edited)
        assertEquals(3L, aligned.map("metadata")["generation"])

        val noMetadata = EditProjection.alignServerFields(linkedMapOf("kind" to "X"), linkedMapOf("kind" to "X"))
        assertEquals(linkedMapOf<String, Any?>("kind" to "X"), noMetadata)
    }

    @Test
    fun `the diff base after alignServerFields is equal to an untouched buffer`() {
        val base = untouched()
        val edited = untouched()
        edited.sub("metadata")["resourceVersion"] = "90"
        assertEquals(EditorYaml.dump(edited), EditorYaml.dump(EditProjection.alignServerFields(base, edited)))
    }

    // ── validate ─────────────────────────────────────────────────────────────

    @Test
    fun `an untouched buffer is valid`() {
        assertEquals(emptyList(), messages(untouched()))
    }

    @Test
    fun `rule 1 a list or a scalar is refused`() {
        val expected = listOf("The editor must hold one YAML mapping (an object), not a list or a scalar.")
        assertEquals(expected, messages(listOf("a")))
        assertEquals(expected, messages("text"))
        assertEquals(expected, messages(5))
        assertEquals(expected, messages(null))
    }

    @Test
    fun `rule 2 apiVersion must stay`() {
        val edited = untouched().apply { put("apiVersion", "apps/v1beta1") }
        assertEquals(listOf("apiVersion must stay \"apps/v1\"."), messages(edited))
        assertEquals(listOf("apiVersion must stay \"apps/v1\"."), messages(untouched().apply { remove("apiVersion") }))
    }

    @Test
    fun `rule 3 kind must stay`() {
        assertEquals(listOf("kind must stay \"Deployment\"."), messages(untouched().apply { put("kind", "StatefulSet") }))
    }

    @Test
    fun `rule 4 metadata must exist and be a mapping`() {
        assertEquals(listOf("metadata is missing."), messages(untouched().apply { remove("metadata") }))
        assertEquals(listOf("metadata is missing."), messages(untouched().apply { put("metadata", "text") }))
        assertEquals(listOf("metadata is missing."), messages(untouched().apply { put("metadata", null) }))
    }

    @Test
    fun `rule 5 the name must stay`() {
        val renamed = untouched().apply { sub("metadata")["name"] = "web-2" }
        assertEquals(listOf("metadata.name must stay \"web\" — renaming creates a different object."), messages(renamed))
        val nameless = untouched().apply { sub("metadata").remove("name") }
        assertEquals(listOf("metadata.name must stay \"web\" — renaming creates a different object."), messages(nameless))
    }

    @Test
    fun `rule 6 the namespace must stay, and a cluster-scoped object takes none`() {
        val moved = untouched().apply { sub("metadata")["namespace"] = "other-ns" }
        assertEquals(listOf("metadata.namespace must stay \"example-ns\"."), messages(moved))

        val dropped = untouched().apply { sub("metadata").remove("namespace") }
        assertEquals(emptyList(), messages(dropped))

        val scoped = EditProjection.visible(node()).apply { sub("metadata")["namespace"] = "example-ns" }
        assertEquals(listOf("metadata.namespace must not be set on a cluster-scoped object."), messages(scoped, node(), setOf("7")))
        assertEquals(emptyList(), messages(EditProjection.visible(node()), node(), setOf("7")))
    }

    @Test
    fun `rule 7 the resourceVersion may be either allowed value, nothing else`() {
        fun withRv(value: Any?) = untouched().apply { sub("metadata")["resourceVersion"] = value }
        val message = "metadata.resourceVersion is managed by the editor; restore \"100\" or delete the line."
        assertEquals(emptyList(), messages(withRv("100")))
        assertEquals(emptyList(), messages(withRv("90")))
        assertEquals(listOf(message), messages(withRv("95")))
        // An unquoted number parses to an Integer, which is not the String the server uses.
        assertEquals(listOf(message), messages(withRv(100)))
        assertEquals(listOf(message), messages(withRv(90L)))
        // Deleting the line is fine.
        assertEquals(emptyList(), messages(untouched().apply { sub("metadata").remove("resourceVersion") }))
    }

    @Test
    fun `rule 8 a status block is refused`() {
        val expected = listOf("status is hidden in this editor and managed by the cluster; remove the status block.")
        assertEquals(expected, messages(untouched().apply { put("status", linkedMapOf("x" to 1)) }))
        assertEquals(expected, messages(untouched().apply { put("status", null) }))
    }

    @Test
    fun `rule 9 managedFields are refused`() {
        val edited = untouched().apply { sub("metadata")["managedFields"] = listOf<Any?>() }
        assertEquals(listOf("metadata.managedFields is hidden in this editor; remove it."), messages(edited))
    }

    @Test
    fun `several problems are reported together in rule order`() {
        val edited = untouched().apply {
            put("kind", "Job")
            put("status", linkedMapOf("x" to 1))
            sub("metadata").apply {
                put("name", "other")
                put("managedFields", listOf<Any?>())
            }
        }
        assertEquals(
            listOf(
                "kind must stay \"Deployment\".",
                "metadata.name must stay \"web\" — renaming creates a different object.",
                "status is hidden in this editor and managed by the cluster; remove the status block.",
                "metadata.managedFields is hidden in this editor; remove it.",
            ),
            messages(edited),
        )
        assertTrue(EditProjection.validate(edited, deployment(), allowed).all { it.line == null })
    }

    // ── body ─────────────────────────────────────────────────────────────────

    @Test
    fun `body re-attaches the base status, pins the base resourceVersion and drops managedFields`() {
        val base = deployment()
        val edited = untouched(base).apply { sub("spec")["replicas"] = 5 }
        val body = EditProjection.body(edited, base)
        assertEquals(base["status"], body["status"])
        assertNotSame(base["status"], body["status"])
        assertEquals("100", body.map("metadata")["resourceVersion"])
        assertFalse(body.map("metadata").containsKey("managedFields"))
        assertEquals(5, body.map("spec")["replicas"])
        assertEquals("example-ns", body.map("metadata")["namespace"])
    }

    @Test
    fun `body pins the base resourceVersion even when the buffer carries the other allowed one`() {
        val base = deployment()
        val edited = untouched(base).apply { sub("metadata")["resourceVersion"] = "90" }
        assertEquals(emptyList(), messages(edited, base))
        assertEquals("100", EditProjection.body(edited, base).map("metadata")["resourceVersion"])
    }

    @Test
    fun `body sets the resourceVersion when the buffer deleted the line`() {
        val base = deployment()
        val edited = untouched(base).apply { sub("metadata").remove("resourceVersion") }
        assertEquals("100", EditProjection.body(edited, base).map("metadata")["resourceVersion"])
    }

    @Test
    fun `body fills the namespace the buffer omitted`() {
        val base = deployment()
        val edited = untouched(base).apply { sub("metadata").remove("namespace") }
        assertEquals("example-ns", EditProjection.body(edited, base).map("metadata")["namespace"])
    }

    @Test
    fun `body of a cluster-scoped object sets no namespace`() {
        val base = node()
        val body = EditProjection.body(EditProjection.visible(base), base)
        assertFalse(body.map("metadata").containsKey("namespace"))
        assertEquals(base["status"], body["status"])
    }

    @Test
    fun `body never contains managedFields even when handed some`() {
        val base = deployment()
        val edited = untouched(base).apply { sub("metadata")["managedFields"] = listOf(linkedMapOf("manager" to "x")) }
        assertFalse(EditProjection.body(edited, base).map("metadata").containsKey("managedFields"))
    }

    @Test
    fun `body of a base without status has no status`() {
        val base = deployment().apply { remove("status") }
        assertFalse(EditProjection.body(untouched(base), base).containsKey("status"))
    }

    @Test
    fun `body does not change its arguments`() {
        val base = deployment()
        val edited = untouched(base)
        val editedBefore = untouched(base)
        EditProjection.body(edited, base)
        assertEquals(deployment(), base)
        assertEquals(editedBefore, edited)
    }

    @Test
    fun `body refuses a base without a resourceVersion`() {
        val base = deployment().apply { sub("metadata")["resourceVersion"] = " " }
        assertFailsWith<IllegalStateException> { EditProjection.body(untouched(base), base) }
        val bare = deployment().apply { sub("metadata").remove("resourceVersion") }
        assertFailsWith<IllegalStateException> { EditProjection.body(untouched(bare), bare) }
    }

    @Test
    fun `hidden fields are not deleted - the custom resource keeps its status`() {
        val base = customResource()
        val edited = EditProjection.visible(base)
        edited.sub("spec")["size"] = "small"
        assertEquals(emptyList(), EditProjection.validate(edited, base, setOf("55")).map { it.message })
        val body = EditProjection.body(edited, base)
        assertEquals(base["status"], body["status"])
        assertEquals("small", body.map("spec")["size"])
        assertFalse(body.map("metadata").containsKey("managedFields"))
        // The unedited case too: an untouched buffer reproduces the status exactly.
        assertEquals(base["status"], EditProjection.body(EditProjection.visible(base), base)["status"])
    }

    @Test
    fun `a buffer that went through the YAML editor round trip still validates and keeps the status`() {
        val base = customResource()
        val text = EditorYaml.dump(EditProjection.visible(base))
        val edited = (EditorYaml.parseSingle(text) as YamlParse.Ok).value
        assertEquals(emptyList(), EditProjection.validate(edited, base, setOf("55")).map { it.message })
        @Suppress("UNCHECKED_CAST")
        assertEquals(base["status"], EditProjection.body(edited as Map<String, Any?>, base)["status"])
    }

    // ── stripForApply ────────────────────────────────────────────────────────

    @Test
    fun `stripForApply removes status and the six server-owned metadata fields`() {
        val doc = deployment()
        doc.sub("metadata")["selfLink"] = "/apis/apps/v1/namespaces/example-ns/deployments/web"
        val stripped = EditProjection.stripForApply(doc)
        assertFalse(stripped.containsKey("status"))
        val metadata = stripped.map("metadata")
        for (field in listOf("managedFields", "resourceVersion", "uid", "creationTimestamp", "generation", "selfLink")) {
            assertFalse(metadata.containsKey(field), field)
        }
        assertEquals(linkedMapOf<String, Any?>("name" to "web", "namespace" to "example-ns", "labels" to linkedMapOf("app" to "web")), metadata)
        assertEquals(doc["spec"], stripped["spec"])
        assertTrue(doc.containsKey("status"))
        assertTrue(doc.map("metadata").containsKey("uid"))
    }
}
