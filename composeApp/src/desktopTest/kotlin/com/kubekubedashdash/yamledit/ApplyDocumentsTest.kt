package com.kubekubedashdash.yamledit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplyDocumentsTest {

    /** The kinds this fake cluster knows; anything else is unknown (null). */
    private val resolver: (String, String) -> ResolvedKind? = { apiVersion, kind ->
        when ("$apiVersion/$kind") {
            "v1/ConfigMap" -> ResolvedKind("", "v1", "ConfigMap", "configmaps", true)
            "v1/Namespace" -> ResolvedKind("", "v1", "Namespace", "namespaces", false)
            "apps/v1/Deployment" -> ResolvedKind("apps", "v1", "Deployment", "deployments", true)
            "example.com/v1/Widget" -> ResolvedKind("example.com", "v1", "Widget", "widgets", true)
            else -> null
        }
    }

    private fun prepare(text: String, defaultNamespace: String = "default") = ApplyDocuments.prepare(text, defaultNamespace, resolver)

    private fun parsed(text: String, defaultNamespace: String = "default") = assertIs<PrepareResult.Parsed>(prepare(text, defaultNamespace))

    private val threeDocuments = """
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: first
          namespace: example-ns
        data:
          a: "1"
        ---
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: second
          namespace: example-ns
        spec:
          replicas: 2
        ---
        apiVersion: v1
        kind: Namespace
        metadata:
          name: third
    """.trimIndent()

    @Test
    fun `three documents become three ApplyDocuments with their start lines`() {
        val result = parsed(threeDocuments)

        assertTrue(result.errors.isEmpty(), "got ${result.errors}")
        assertEquals(listOf(0, 1, 2), result.documents.map { it.index })
        assertEquals(listOf(1, 9, 17), result.documents.map { it.line })
        assertEquals(listOf("ConfigMap", "Deployment", "Namespace"), result.documents.map { it.target.kind })
        assertEquals(listOf("example-ns/first", "example-ns/second", "third"), result.documents.map { it.target.ref })
        assertEquals("/apis/apps/v1/namespaces/example-ns/deployments/second", result.documents[1].target.path())
        assertTrue(result.documents.none { it.namespaceDefaulted })
    }

    @Test
    fun `a body is stripped of server-owned fields and keeps everything else`() {
        val result = parsed(
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: demo-cm
              namespace: example-ns
              uid: 00000000-0000-0000-0000-000000000001
              resourceVersion: "5"
              generation: 2
              creationTimestamp: "2026-01-01T00:00:00Z"
              selfLink: /x
              labels:
                app: demo
              managedFields:
                - manager: kubectl
            data:
              a: "1"
            status:
              phase: Ready
            """.trimIndent(),
        )

        val body = result.documents.single().body
        assertEquals(linkedMapOf("name" to "demo-cm", "namespace" to "example-ns", "labels" to linkedMapOf("app" to "demo")), body["metadata"])
        assertEquals(linkedMapOf("a" to "1"), body["data"])
        assertFalse(body.containsKey("status"))
    }

    @Test
    fun `a v1 List is expanded and every item gets the List's line`() {
        val result = parsed(
            """
            # a list
            apiVersion: v1
            kind: List
            items:
              - apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: one
                  namespace: example-ns
              - apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: two
                  namespace: example-ns
            ---
            apiVersion: v1
            kind: Namespace
            metadata:
              name: after
            """.trimIndent(),
        )

        assertEquals(listOf("one", "two", "after"), result.documents.map { it.target.name })
        assertEquals(listOf(0, 1, 2), result.documents.map { it.index })
        assertEquals(listOf(2, 2, 16), result.documents.map { it.line })
    }

    @Test
    fun `an empty List and an empty input have nothing to apply`() {
        val emptyList = assertIs<PrepareResult.Failed>(prepare("apiVersion: v1\nkind: List\nitems: []\n"))
        assertEquals("Nothing to apply.", emptyList.problem.message)

        val onlySeparators = assertIs<PrepareResult.Failed>(prepare("---\n---\n"))
        assertEquals(YamlProblem(1, 1, "Nothing to apply."), onlySeparators.problem)
    }

    @Test
    fun `a namespaced document without a namespace gets the default and is flagged`() {
        val result = parsed("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo-cm\n", defaultNamespace = "chosen-ns")

        val document = result.documents.single()
        assertEquals("chosen-ns", document.target.namespace)
        assertTrue(document.namespaceDefaulted)
        assertEquals("chosen-ns", (document.body["metadata"] as Map<*, *>)["namespace"], "the body carries the namespace it will be applied to")
    }

    @Test
    fun `a document's own namespace wins over the default`() {
        val result = parsed("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo-cm\n  namespace: own-ns\n", defaultNamespace = "chosen-ns")

        val document = result.documents.single()
        assertEquals("own-ns", document.target.namespace)
        assertFalse(document.namespaceDefaulted)
    }

    @Test
    fun `a cluster-scoped kind with a namespace is an error and without one is fine`() {
        val bad = parsed("apiVersion: v1\nkind: Namespace\nmetadata:\n  name: demo\n  namespace: example-ns\n")
        assertTrue(bad.documents.isEmpty())
        assertEquals("Namespace is cluster-scoped; remove metadata.namespace.", bad.errors.single().message)
        assertEquals("Namespace example-ns/demo", bad.errors.single().label)

        val good = parsed("apiVersion: v1\nkind: Namespace\nmetadata:\n  name: demo\n")
        val document = good.documents.single()
        assertNull(document.target.namespace)
        assertFalse(document.namespaceDefaulted)
        assertFalse((document.body["metadata"] as Map<*, *>).containsKey("namespace"), "a default namespace must not leak into a cluster-scoped body")
    }

    @Test
    fun `an unknown kind is an error naming the apiVersion and kind`() {
        val result = parsed("apiVersion: example.com/v2\nkind: Gizmo\nmetadata:\n  name: g\n")

        assertEquals("Unknown kind example.com/v2 Gizmo on this cluster.", result.errors.single().message)
        assertEquals("Gizmo g", result.errors.single().label)
    }

    @Test
    fun `generateName is not supported`() {
        val result = parsed("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  generateName: demo-\n")

        assertEquals("metadata.generateName is not supported; set metadata.name.", result.errors.single().message)
    }

    @Test
    fun `a document missing apiVersion, kind or name says which`() {
        val result = parsed(
            """
            kind: ConfigMap
            metadata:
              name: a
            ---
            apiVersion: v1
            metadata:
              name: b
            ---
            apiVersion: v1
            kind: ConfigMap
            metadata:
              labels:
                x: y
            ---
            - not
            - a mapping
            """.trimIndent(),
        )

        assertEquals(
            listOf("apiVersion is missing.", "kind is missing.", "metadata.name is missing.", "Each document must be a YAML mapping (an object)."),
            result.errors.map { it.message },
        )
        assertEquals(listOf(0, 1, 2, 3), result.errors.map { it.index })
        assertEquals(listOf(1, 5, 9, 15), result.errors.map { it.line })
        assertEquals("Document 4", result.errors.last().label)
    }

    @Test
    fun `a second document with the same target is a duplicate of the first`() {
        val result = parsed(
            """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: same
              namespace: example-ns
            ---
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: other
              namespace: example-ns
            ---
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: same
              namespace: example-ns
            """.trimIndent(),
        )

        assertEquals(listOf("same", "other"), result.documents.map { it.target.name })
        assertEquals("Duplicate of document 1.", result.errors.single().message)
        assertEquals(2, result.errors.single().index)
        assertEquals("ConfigMap example-ns/same", result.errors.single().label)
    }

    @Test
    fun `a document that fails does not stop the others`() {
        val result = parsed("apiVersion: v1\nkind: Gizmo\nmetadata:\n  name: x\n---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: ok\n")

        assertEquals(listOf("ok"), result.documents.map { it.target.name })
        assertEquals(1, result.errors.size)
        assertEquals(1, result.documents.single().index, "index counts the failed document too")
    }

    @Test
    fun `a name with characters that would change the path is an error, not a crash`() {
        val result = parsed("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: a/b\n  namespace: example-ns\n")

        assertEquals("metadata.name contains characters that are not allowed.", result.errors.single().message)
    }

    @Test
    fun `a parse error is Failed with its position`() {
        val result = assertIs<PrepareResult.Failed>(prepare("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: a\n  name: b\n"))

        assertEquals(5, result.problem.line)
        assertTrue(result.problem.message.isNotBlank())
    }

    // ── deepMerge ─────────────────────────────────────────────────────────────

    @Test
    fun `deepMerge merges maps recursively and replaces lists and scalars`() {
        val base = linkedMapOf<String, Any?>(
            "keep" to 1,
            "scalar" to "old",
            "list" to listOf("a", "b"),
            "map" to linkedMapOf<String, Any?>("x" to 1, "y" to linkedMapOf<String, Any?>("deep" to true)),
        )
        val overlay = linkedMapOf<String, Any?>(
            "scalar" to "new",
            "list" to listOf("c"),
            "map" to linkedMapOf<String, Any?>("z" to 3, "y" to linkedMapOf<String, Any?>("other" to false)),
            "added" to "yes",
        )

        val merged = deepMerge(base, overlay)

        assertEquals(
            linkedMapOf<String, Any?>(
                "keep" to 1,
                "scalar" to "new",
                "list" to listOf("c"),
                "map" to linkedMapOf<String, Any?>("x" to 1, "y" to linkedMapOf<String, Any?>("deep" to true, "other" to false), "z" to 3),
                "added" to "yes",
            ),
            merged,
        )
    }

    @Test
    fun `deepMerge changes neither input and a null overlay value replaces`() {
        fun base() = linkedMapOf<String, Any?>("map" to linkedMapOf<String, Any?>("x" to 1), "gone" to "here")
        fun overlay() = linkedMapOf<String, Any?>("map" to linkedMapOf<String, Any?>("x" to 2), "gone" to null)
        val baseInput = base()
        val overlayInput = overlay()

        val merged = deepMerge(baseInput, overlayInput)

        assertEquals(base(), baseInput)
        assertEquals(overlay(), overlayInput)
        assertEquals(2, (merged["map"] as Map<*, *>)["x"])
        assertTrue(merged.containsKey("gone") && merged["gone"] == null)
        assertNotSame(baseInput["map"], merged["map"])
        assertNotSame(overlayInput["map"], merged["map"])
    }
}
