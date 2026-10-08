package com.kubekubedashdash.yamledit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditableKindsTest {

    private fun target(kind: String, name: String, namespace: String?, group: String? = null, version: String? = null, plural: String? = null) = EditableKinds.targetFor(kind, name, namespace, group, version, plural)

    @Test
    fun `a ConfigMap is a core v1 namespaced target`() {
        val t = assertNotNull(target("ConfigMap", "demo-cm", "example-ns"))
        assertEquals(EditTarget("ConfigMap", "", "v1", "configmaps", true, "demo-cm", "example-ns"), t)
        assertEquals("v1", t.apiVersion)
        assertEquals("/api/v1/namespaces/example-ns/configmaps/demo-cm", t.path())
        assertEquals("/api/v1/namespaces/example-ns/configmaps", t.collectionPath())
        assertEquals("example-ns/demo-cm", t.ref)
        assertEquals("/v1/configmaps/example-ns/demo-cm", t.key)
    }

    @Test
    fun `the Endpoint router label is an alias of Endpoints`() {
        val t = assertNotNull(target("Endpoint", "demo-svc", "example-ns"))
        assertEquals("Endpoints", t.kind)
        assertEquals("endpoints", t.plural)
        assertEquals("/api/v1/namespaces/example-ns/endpoints/demo-svc", t.path())
        assertEquals("Endpoints", assertNotNull(target("Endpoints", "demo-svc", "example-ns")).kind)
    }

    @Test
    fun `the kind is matched case-insensitively and the canonical spelling is used`() {
        val t = assertNotNull(target("deployment", "web", "example-ns", group = ""))
        assertEquals("Deployment", t.kind)
        assertEquals("apps/v1", t.apiVersion)
        assertEquals("/apis/apps/v1/namespaces/example-ns/deployments/web", t.path())
    }

    @Test
    fun `an Event and an unknown kind without a group are not editable`() {
        assertNull(target("Event", "demo-event", "example-ns"))
        assertNull(target("Gizmo", "demo-gizmo", "example-ns"))
        assertNull(target("Gizmo", "demo-gizmo", "example-ns", group = " ", version = "v1"))
    }

    @Test
    fun `a custom resource with group and version gets the default plural or the given one`() {
        val defaulted = assertNotNull(target("SparkApplication", "job-1", "example-ns", group = "example.com", version = "v1beta2"))
        assertEquals("sparkapplications", defaulted.plural)
        assertEquals("example.com/v1beta2", defaulted.apiVersion)
        assertEquals("/apis/example.com/v1beta2/namespaces/example-ns/sparkapplications/job-1", defaulted.path())
        assertEquals("/apis/example.com/v1beta2/namespaces/example-ns/sparkapplications", defaulted.collectionPath())

        val explicit = assertNotNull(target("Policy", "p1", "example-ns", group = "example.com", version = "v1", plural = "polices"))
        assertEquals("polices", explicit.plural)
        assertEquals("policies", assertNotNull(target("Policy", "p1", "example-ns", group = "example.com", version = "v1", plural = " ")).plural)
    }

    @Test
    fun `a cluster-scoped custom resource has no namespace segment`() {
        val t = assertNotNull(target("Widget", "w1", null, group = "example.com", version = "v1"))
        assertEquals(false, t.namespaced)
        assertEquals("/apis/example.com/v1/widgets/w1", t.path())
        assertEquals("w1", t.ref)
    }

    @Test
    fun `a group-qualified kind never reaches the built-in table`() {
        val t = assertNotNull(target("Ingress", "i1", "example-ns", group = "example.com", version = "v1"))
        assertEquals("example.com", t.group)
        assertEquals("/apis/example.com/v1/namespaces/example-ns/ingresses/i1", t.path())
    }

    @Test
    fun `a custom resource without a version is not editable`() {
        assertNull(target("SparkApplication", "job-1", "example-ns", group = "example.com"))
        assertNull(target("SparkApplication", "job-1", "example-ns", group = "example.com", version = ""))
    }

    @Test
    fun `a built-in whose scope disagrees with the namespace is not editable`() {
        assertNull(target("ConfigMap", "demo-cm", null))
        assertNull(target("Node", "node-1", "example-ns"))
        assertNotNull(target("Node", "node-1", null))
    }

    @Test
    fun `a name or namespace that is not a plain path segment is refused`() {
        assertNull(target("ConfigMap", "a/b", "example-ns"))
        assertNull(target("ConfigMap", "a b", "example-ns"))
        assertNull(target("ConfigMap", "a?x", "example-ns"))
        assertNull(target("ConfigMap", "a%2Fb", "example-ns"))
        assertNull(target("ConfigMap", "", "example-ns"))
        assertNull(target("ConfigMap", "demo-cm", "ns/../x"))
        assertNull(target("Widget", "a#b", null, group = "example.com", version = "v1"))
    }

    @Test
    fun `EditTarget refuses a scope that disagrees with its namespace`() {
        assertFailsWith<IllegalArgumentException> { EditTarget("ConfigMap", "", "v1", "configmaps", true, "demo-cm", null) }
        assertFailsWith<IllegalArgumentException> { EditTarget("Node", "", "v1", "nodes", false, "node-1", "example-ns") }
    }

    @Test
    fun `every built-in resolves to a target of the right scope`() {
        assertEquals(35, EditableKinds.BUILT_INS.size)
        assertEquals(EditableKinds.BUILT_INS.size, EditableKinds.BUILT_INS.map { it.kind }.toSet().size)
        for (b in EditableKinds.BUILT_INS) {
            val ns = if (b.namespaced) "example-ns" else null
            val t = assertNotNull(target(b.kind, "demo-1", ns), b.kind)
            assertEquals(b.kind, t.kind)
            assertEquals(b.namespaced, t.namespaced)
            assertTrue(t.path().endsWith("/${b.plural}/demo-1"), t.path())
            assertEquals(b, EditableKinds.builtIn(b.group, b.kind))
        }
    }

    @Test
    fun `the table matches the versions the YAML tab reads`() {
        val hpa = assertNotNull(EditableKinds.builtIn("autoscaling", "HorizontalPodAutoscaler"))
        assertEquals("v2", hpa.version)
        assertEquals("policy", assertNotNull(EditableKinds.builtIn("policy", "PodDisruptionBudget")).group)
        assertEquals("customresourcedefinitions", assertNotNull(EditableKinds.builtIn("apiextensions.k8s.io", "CustomResourceDefinition")).plural)
        assertEquals("networkpolicies", assertNotNull(EditableKinds.builtIn("networking.k8s.io", "NetworkPolicy")).plural)
        assertEquals("endpointslices", assertNotNull(EditableKinds.builtIn("discovery.k8s.io", "EndpointSlice")).plural)
    }

    @Test
    fun `builtIn matches on group and kind exactly`() {
        assertNull(EditableKinds.builtIn("", "Deployment"))
        assertNull(EditableKinds.builtIn("apps", "deployment"))
        assertNull(EditableKinds.builtIn("example.com", "ConfigMap"))
        assertNull(EditableKinds.builtIn("", "Event"))
    }
}
