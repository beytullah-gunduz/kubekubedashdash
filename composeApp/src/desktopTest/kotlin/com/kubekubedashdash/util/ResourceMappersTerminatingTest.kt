package com.kubekubedashdash.util

import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A claim or volume the API has accepted a delete for keeps its phase
 * ("Bound") until its protection finalizer clears; the list showed that
 * phase and nothing else (review follow-up F6). The row now reads
 * "Terminating" while a deletion timestamp is set, as pods already do.
 */
class ResourceMappersTerminatingTest {

    @Test
    fun `a claim with a deletion timestamp reads Terminating, one without keeps its phase`() {
        val terminating = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("ns").withUid("u1").withDeletionTimestamp("2026-01-01T00:00:00Z").endMetadata()
            .withNewStatus().withPhase("Bound").addToCapacity("storage", Quantity("1Gi")).endStatus()
            .build()
        val live = PersistentVolumeClaimBuilder()
            .withNewMetadata().withName("data").withNamespace("ns").withUid("u2").endMetadata()
            .withNewStatus().withPhase("Bound").endStatus()
            .build()

        val t = ResourceMappers.mapPersistentVolumeClaim(terminating)
        val l = ResourceMappers.mapPersistentVolumeClaim(live)

        assertEquals("Terminating", t.status)
        assertEquals("data", t.name)
        assertEquals("ns", t.namespace)
        assertEquals("1Gi", t.extraColumns["Capacity"])
        assertEquals("Bound", l.status)
    }

    @Test
    fun `a volume with a deletion timestamp reads Terminating, one without keeps its phase`() {
        val terminating = PersistentVolumeBuilder()
            .withNewMetadata().withName("pv-1").withUid("u1").withDeletionTimestamp("2026-01-01T00:00:00Z").endMetadata()
            .withNewStatus().withPhase("Bound").endStatus()
            .build()
        val live = PersistentVolumeBuilder()
            .withNewMetadata().withName("pv-1").withUid("u2").endMetadata()
            .withNewStatus().withPhase("Available").endStatus()
            .build()

        assertEquals("Terminating", ResourceMappers.mapPersistentVolume(terminating).status)
        assertEquals("Available", ResourceMappers.mapPersistentVolume(live).status)
    }

    @Test
    fun `a custom resource with a deletion timestamp reads Terminating, one without has no status`() {
        val crd = CrdInfo(group = "widgets.example", version = "v1", kind = "Widget", plural = "widgets", singular = "widget", shortNames = emptyList(), categories = emptyList(), scope = CrdScope.NAMESPACED, columns = emptyList())
        val terminating = GenericKubernetesResourceBuilder().withApiVersion("widgets.example/v1").withKind("Widget")
            .withNewMetadata().withName("w").withNamespace("ns").withUid("u1").withDeletionTimestamp("2026-01-01T00:00:00Z").endMetadata().build()
        val live = GenericKubernetesResourceBuilder().withApiVersion("widgets.example/v1").withKind("Widget")
            .withNewMetadata().withName("w").withNamespace("ns").withUid("u2").endMetadata().build()

        assertEquals("Terminating", ResourceMappers.mapCrInstance(terminating, crd).status)
        assertNull(ResourceMappers.mapCrInstance(live, crd).status)
    }
}
