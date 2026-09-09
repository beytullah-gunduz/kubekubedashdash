package com.kubekubedashdash.util

import com.kubekubedashdash.models.OwnerRefInfo
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An owner reference carries its owner's API group (review follow-up F16),
 * taken from `apiVersion`, so a custom resource that reuses a built-in kind
 * name is never taken for the built-in downstream. A reference without an
 * apiVersion is dropped, as one without a kind, a name or a uid already was.
 */
class ResourceMappersOwnerGroupTest {

    private fun ref(apiVersion: String?, kind: String = "Deployment") = OwnerReferenceBuilder()
        .withApiVersion(apiVersion)
        .withKind(kind)
        .withName("frontend")
        .withUid("dep-1")
        .withController(true)
        .build()

    @Test
    fun `the group is the part of apiVersion before the slash`() {
        assertEquals("apps", ResourceMappers.mapOwnerRefs(listOf(ref("apps/v1"))).single().group)
        assertEquals("batch", ResourceMappers.mapOwnerRefs(listOf(ref("batch/v1", kind = "Job"))).single().group)
        assertEquals("example.io", ResourceMappers.mapOwnerRefs(listOf(ref("example.io/v1alpha1"))).single().group)
    }

    @Test
    fun `a core owner carries the empty group`() {
        assertEquals("", ResourceMappers.mapOwnerRefs(listOf(ref("v1", kind = "Pod"))).single().group)
    }

    @Test
    fun `a reference without an apiVersion is dropped like one without a uid`() {
        assertTrue(ResourceMappers.mapOwnerRefs(listOf(ref(null), ref(""), ref("  "))).isEmpty())
    }

    @Test
    fun `the other fields are carried unchanged`() {
        assertEquals(
            OwnerRefInfo(kind = "Deployment", name = "frontend", uid = "dep-1", controller = true, group = "apps"),
            ResourceMappers.mapOwnerRefs(listOf(ref("apps/v1"))).single(),
        )
    }
}
