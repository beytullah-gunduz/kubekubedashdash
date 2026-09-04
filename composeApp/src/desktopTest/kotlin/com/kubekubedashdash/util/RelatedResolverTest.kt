package com.kubekubedashdash.util

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the pure relation resolver ([ownerChain], [childrenOf],
 * [servicesFor]) that backs the Related section — WS1 of the review-item-12
 * plan. No Compose, no fabric8, no API calls: everything here works off the
 * cached domain models.
 */
class RelatedResolverTest {

    private fun pod(
        uid: String,
        name: String = uid,
        namespace: String = "example-ns",
        owners: List<OwnerRefInfo> = emptyList(),
    ) = PodInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        status = "Running",
        ready = "1/1",
        restarts = 0,
        age = "1h",
        node = "node-1",
        ip = "10.0.0.1",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = emptyList(),
        owners = owners,
    )

    private fun generic(
        uid: String,
        name: String = uid,
        namespace: String? = "example-ns",
        owners: List<OwnerRefInfo> = emptyList(),
    ) = GenericResourceInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        status = null,
        age = "1h",
        labels = emptyMap(),
        annotations = emptyMap(),
        owners = owners,
    )

    private fun service(
        uid: String,
        name: String = uid,
        namespace: String = "example-ns",
        selector: Map<String, String>,
    ) = ServiceInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        type = "ClusterIP",
        clusterIP = "10.0.0.1",
        ports = "80/TCP",
        age = "1h",
        selector = selector,
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    // ── ownerChain ──────────────────────────────────────────────────────────

    @Test
    fun `ownerChain returns a two-hop Pod to ReplicaSet to Deployment chain nearest first`() {
        val podOwners = listOf(OwnerRefInfo(kind = "ReplicaSet", name = "frontend-7d9", uid = "rs-1"))
        val rsOwners = mapOf(
            "rs-1" to listOf(OwnerRefInfo(kind = "Deployment", name = "frontend", uid = "dep-1")),
        )

        val chain = ownerChain(
            start = podOwners,
            namespace = "example-ns",
            lookupOwners = { uid -> rsOwners[uid] },
        )

        assertEquals(2, chain.size)
        assertEquals(RelatedRef(kind = "ReplicaSet", name = "frontend-7d9", namespace = "example-ns", uid = "rs-1"), chain[0])
        assertEquals(RelatedRef(kind = "Deployment", name = "frontend", namespace = "example-ns", uid = "dep-1"), chain[1])
    }

    @Test
    fun `ownerChain yields empty for a resource with no owners`() {
        val chain = ownerChain(start = emptyList(), namespace = "example-ns", lookupOwners = { null })
        assertTrue(chain.isEmpty())
    }

    @Test
    fun `ownerChain ends the chain including the last hop when lookupOwners returns null mid-walk`() {
        val podOwners = listOf(OwnerRefInfo(kind = "ReplicaSet", name = "frontend-7d9", uid = "rs-1"))

        // The cache does not know rs-1's owners (e.g. its informer isn't running).
        val chain = ownerChain(start = podOwners, namespace = "example-ns", lookupOwners = { null })

        assertEquals(1, chain.size)
        assertEquals("rs-1", chain[0].uid)
    }

    @Test
    fun `ownerChain terminates on a cycle without repeating a uid`() {
        val startOwners = listOf(OwnerRefInfo(kind = "Kind", name = "a", uid = "a"))
        val lookups = mapOf(
            "a" to listOf(OwnerRefInfo(kind = "Kind", name = "b", uid = "b")),
            "b" to listOf(OwnerRefInfo(kind = "Kind", name = "a", uid = "a")),
        )

        val chain = ownerChain(start = startOwners, namespace = null, lookupOwners = { uid -> lookups[uid] })

        assertEquals(listOf("a", "b"), chain.map { it.uid })
    }

    @Test
    fun `ownerChain stops at maxDepth even when the walk could continue`() {
        val startOwners = listOf(OwnerRefInfo(kind = "Kind", name = "n0", uid = "u0"))
        // Each uid points to a distinct next uid, so this walk never cycles on its own —
        // only the maxDepth cap should stop it.
        val lookups = (0 until 10).associate { i ->
            "u$i" to listOf(OwnerRefInfo(kind = "Kind", name = "n${i + 1}", uid = "u${i + 1}"))
        }

        val chain = ownerChain(start = startOwners, namespace = null, lookupOwners = { uid -> lookups[uid] }, maxDepth = 3)

        assertEquals(3, chain.size)
        assertEquals(listOf("u0", "u1", "u2"), chain.map { it.uid })
    }

    // ── childrenOf ──────────────────────────────────────────────────────────

    @Test
    fun `childrenOf returns pods owned by the uid and excludes others`() {
        val owned = pod(uid = "pod-1", owners = listOf(OwnerRefInfo(kind = "ReplicaSet", name = "rs", uid = "rs-1")))
        val unrelated = pod(uid = "pod-2", owners = listOf(OwnerRefInfo(kind = "ReplicaSet", name = "other", uid = "rs-2")))
        val unowned = pod(uid = "pod-3")

        val children = childrenOf(
            uid = "rs-1",
            pods = listOf(owned, unrelated, unowned),
            replicaSets = emptyList(),
            includeReplicaSets = false,
        )

        assertEquals(listOf("pod-1"), children.map { it.uid })
    }

    @Test
    fun `childrenOf adds owned ReplicaSets and their pods for a Deployment when includeReplicaSets is true`() {
        val ownedRs = generic(uid = "rs-1", owners = listOf(OwnerRefInfo(kind = "Deployment", name = "frontend", uid = "dep-1")))
        val otherRs = generic(uid = "rs-2", owners = listOf(OwnerRefInfo(kind = "Deployment", name = "other", uid = "dep-2")))
        val podUnderOwnedRs = pod(uid = "pod-1", owners = listOf(OwnerRefInfo(kind = "ReplicaSet", name = "frontend-rs", uid = "rs-1")))
        val podUnderOtherRs = pod(uid = "pod-2", owners = listOf(OwnerRefInfo(kind = "ReplicaSet", name = "other-rs", uid = "rs-2")))

        val children = childrenOf(
            uid = "dep-1",
            pods = listOf(podUnderOwnedRs, podUnderOtherRs),
            replicaSets = listOf(ownedRs, otherRs),
            includeReplicaSets = true,
        )

        assertEquals(setOf("rs-1", "pod-1"), children.map { it.uid }.toSet())
        assertEquals(2, children.size)
    }

    @Test
    fun `childrenOf omits ReplicaSets when includeReplicaSets is false even if one is owned`() {
        val ownedRs = generic(uid = "rs-1", owners = listOf(OwnerRefInfo(kind = "Deployment", name = "frontend", uid = "dep-1")))

        val children = childrenOf(
            uid = "dep-1",
            pods = emptyList(),
            replicaSets = listOf(ownedRs),
            includeReplicaSets = false,
        )

        assertTrue(children.isEmpty())
    }

    @Test
    fun `childrenOf never returns an unowned pod`() {
        val unowned = pod(uid = "pod-1")

        val children = childrenOf(uid = "rs-1", pods = listOf(unowned), replicaSets = emptyList(), includeReplicaSets = false)

        assertTrue(children.isEmpty())
    }

    // ── servicesFor ─────────────────────────────────────────────────────────

    @Test
    fun `servicesFor matches a Service whose selector is a subset of the labels`() {
        val labels = mapOf("app" to "frontend", "tier" to "web")
        val svc = service(uid = "svc-1", selector = mapOf("app" to "frontend"))

        val result = servicesFor(labels, listOf(svc))

        assertEquals(listOf("svc-1"), result.map { it.uid })
    }

    @Test
    fun `servicesFor excludes a Service whose selector has an extra key`() {
        val labels = mapOf("app" to "frontend")
        val svc = service(uid = "svc-1", selector = mapOf("app" to "frontend", "tier" to "web"))

        val result = servicesFor(labels, listOf(svc))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `servicesFor never matches a Service with an empty selector`() {
        val labels = mapOf("app" to "frontend")
        val headless = service(uid = "svc-1", selector = emptyMap())

        val result = servicesFor(labels, listOf(headless))

        assertTrue(result.isEmpty())
    }
}
