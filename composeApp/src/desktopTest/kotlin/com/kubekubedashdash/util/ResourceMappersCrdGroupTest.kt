package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A CRD whose `spec.group` is missing or blank used to be listed with
 * `group = ""`, which every guarded dispatch reads as "no group, built-in"
 * (review follow-up F13). The API requires a group, so such an object is
 * malformed; the mapper now skips it, as it skips a CRD with no served
 * version or no kind.
 */
class ResourceMappersCrdGroupTest {

    private fun crd(group: String?): CustomResourceDefinition {
        val spec = CustomResourceDefinitionBuilder()
            .withNewMetadata().withName("widgets.example.com").endMetadata()
            .withNewSpec()
            .withScope("Namespaced")
            .withNewNames().withKind("Widget").withPlural("widgets").withSingular("widget").endNames()
            .addNewVersion().withName("v1").withServed(true).withStorage(true).endVersion()
        return (if (group != null) spec.withGroup(group) else spec).endSpec().build()
    }

    @Test
    fun `a CRD without a group is not listed`() {
        assertNull(ResourceMappers.mapCrd(crd(null)), "a missing group must not read as a built-in")
        assertNull(ResourceMappers.mapCrd(crd("")), "a blank group must not read as a built-in")
        assertNull(ResourceMappers.mapCrd(crd("   ")))
    }

    @Test
    fun `a CRD with a group still maps`() {
        assertEquals("example.com", ResourceMappers.mapCrd(crd("example.com"))?.group)
    }
}
