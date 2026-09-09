package com.kubekubedashdash.util

import com.kubekubedashdash.models.OwnerRefInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The owner chain carries each hop's API group, and the guard the Related
 * section subscribes informers by tells a built-in owner from a custom
 * resource that reuses its name (review follow-up F16).
 */
class RelatedResolverGroupTest {

    @Test
    fun `ownerChain carries each hop's group`() {
        val podOwners = listOf(OwnerRefInfo(kind = "ReplicaSet", name = "frontend-7d9", uid = "rs-1", group = "apps"))
        val lookup = mapOf("rs-1" to listOf(OwnerRefInfo(kind = "Deployment", name = "frontend", uid = "dep-1", controller = true, group = "apps")))

        val chain = ownerChain(podOwners, "example-ns", lookupOwners = { lookup[it] })

        assertEquals(RelatedRef(kind = "ReplicaSet", name = "frontend-7d9", namespace = "example-ns", uid = "rs-1", group = "apps"), chain[0])
        assertEquals(RelatedRef(kind = "Deployment", name = "frontend", namespace = "example-ns", uid = "dep-1", group = "apps"), chain[1])
    }

    @Test
    fun `a custom owner keeps its own group through the chain`() {
        val owners = listOf(OwnerRefInfo(kind = "ReplicaSet", name = "shadow", uid = "cr-1", group = "example.io"))

        assertEquals("example.io", ownerChain(owners, "example-ns", lookupOwners = { null }).single().group)
    }

    @Test
    fun `isBuiltIn needs the kind and the built-in's group, or no group at all`() {
        assertTrue(OwnerRefInfo("ReplicaSet", "rs", "rs-1", group = "apps").isBuiltIn("ReplicaSet"))
        assertTrue(OwnerRefInfo("ReplicaSet", "rs", "rs-1").isBuiltIn("ReplicaSet"), "a reference built without a group is the built-in, as before")
        assertFalse(OwnerRefInfo("ReplicaSet", "rs", "rs-1", group = "example.io").isBuiltIn("ReplicaSet"))
        assertFalse(OwnerRefInfo("ReplicaSet", "rs", "rs-1", group = "").isBuiltIn("ReplicaSet"), "there is no core-group ReplicaSet")
        assertFalse(OwnerRefInfo("Job", "j", "j-1", group = "batch").isBuiltIn("ReplicaSet"))
        assertTrue(OwnerRefInfo("Job", "j", "j-1", group = "batch").isBuiltIn("Job"))
    }
}
