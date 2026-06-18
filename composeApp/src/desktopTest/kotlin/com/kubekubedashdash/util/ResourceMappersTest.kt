package com.kubekubedashdash.util

import com.kubekubedashdash.models.CrdScope
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the pure mappers extracted from `ReactiveKubeClient`
 * (audit A1). Before the extraction this logic was welded to a live
 * `KubernetesClient` and effectively untestable; these are the first
 * unit tests over it.
 */
class ResourceMappersTest {

    // ── effectivePodStatus ──────────────────────────────────────────────────

    @Test
    fun `effectivePodStatus returns Unknown when phase is absent`() {
        val pod = PodBuilder().withNewMetadata().withName("p").endMetadata().build()
        assertEquals("Unknown", ResourceMappers.effectivePodStatus(pod))
    }

    @Test
    fun `effectivePodStatus surfaces a waiting container reason over the phase`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus()
            .withPhase("Pending")
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("c")
                    .withNewState().withNewWaiting().withReason("CrashLoopBackOff").endWaiting().endState()
                    .build(),
            )
            .endStatus()
            .build()
        assertEquals("CrashLoopBackOff", ResourceMappers.effectivePodStatus(pod))
    }

    @Test
    fun `effectivePodStatus ignores terminated reason when phase is Succeeded`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus()
            .withPhase("Succeeded")
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("c")
                    .withNewState().withNewTerminated().withReason("Completed").endTerminated().endState()
                    .build(),
            )
            .endStatus()
            .build()
        assertEquals("Succeeded", ResourceMappers.effectivePodStatus(pod))
    }

    @Test
    fun `effectivePodStatus surfaces terminated reason when phase is not Succeeded`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus()
            .withPhase("Running")
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("c")
                    .withNewState().withNewTerminated().withReason("Error").withExitCode(1).endTerminated().endState()
                    .build(),
            )
            .endStatus()
            .build()
        assertEquals("Error", ResourceMappers.effectivePodStatus(pod))
    }

    @Test
    fun `effectivePodStatus keeps Running when a container completed cleanly`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("p").endMetadata()
            .withNewStatus()
            .withPhase("Running")
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("sidecar")
                    .withNewState().withNewTerminated().withReason("Completed").withExitCode(0).endTerminated().endState()
                    .build(),
                ContainerStatusBuilder()
                    .withName("app")
                    .withNewState().withNewRunning().endRunning().endState()
                    .build(),
            )
            .endStatus()
            .build()
        assertEquals("Running", ResourceMappers.effectivePodStatus(pod))
    }

    // ── mapPod ──────────────────────────────────────────────────────────────

    @Test
    fun `mapPod computes ready ratio, restart sum, container state and timestamp`() {
        val pod = PodBuilder()
            .withNewMetadata()
            .withName("web-0")
            .withNamespace("prod")
            .withUid("uid-1")
            .withCreationTimestamp("2026-01-01T00:00:00Z")
            .endMetadata()
            .withNewSpec()
            .withNodeName("node-a")
            .withContainers(
                ContainerBuilder().withName("app").withImage("app:1").build(),
                ContainerBuilder().withName("sidecar").withImage("envoy:2").build(),
            )
            .endSpec()
            .withNewStatus()
            .withPhase("Running")
            .withPodIP("10.0.0.5")
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("app").withReady(true).withRestartCount(2)
                    .withNewState().withNewRunning().endRunning().endState()
                    .build(),
                ContainerStatusBuilder()
                    .withName("sidecar").withReady(false).withRestartCount(1)
                    .withNewState().withNewWaiting().withReason("PodInitializing").endWaiting().endState()
                    .build(),
            )
            .endStatus()
            .build()

        val info = ResourceMappers.mapPod(pod)

        assertEquals("web-0", info.name)
        assertEquals("prod", info.namespace)
        assertEquals("uid-1", info.uid)
        assertEquals("1/2", info.ready)
        assertEquals(3, info.restarts)
        assertEquals("node-a", info.node)
        assertEquals("10.0.0.5", info.ip)
        assertEquals("Running", info.phase)
        assertEquals("2026-01-01T00:00:00Z", info.creationTimestamp)
        assertEquals(2, info.containers.size)
        assertEquals("Running", info.containers[0].state)
        assertEquals("PodInitializing", info.containers[1].state)
    }

    // ── mapEvent ────────────────────────────────────────────────────────────

    @Test
    fun `mapEvent returns null when there is no usable timestamp`() {
        val ev = EventBuilder().withNewMetadata().withName("e").endMetadata().build()
        assertNull(ResourceMappers.mapEvent(ev))
    }

    @Test
    fun `mapEvent builds objectRef and applies defaults`() {
        val ev = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("kube-system").withUid("ev-1").endMetadata()
            .withLastTimestamp("2026-02-02T10:00:00Z")
            .withInvolvedObject(
                ObjectReferenceBuilder().withKind("Pod").withName("web-0").build(),
            )
            .withMessage("Back-off restarting failed container")
            .build()

        val info = ResourceMappers.mapEvent(ev)!!
        assertEquals("Pod/web-0", info.objectRef)
        assertEquals("Pod", info.objectKind)
        assertEquals("web-0", info.objectName)
        assertEquals("Normal", info.type) // default when ev.type is null
        assertEquals(1, info.count) // default when ev.count is null
        assertEquals("kube-system", info.namespace)
    }

    // ── mapCrd ──────────────────────────────────────────────────────────────

    @Test
    fun `mapCrd picks the storage version and maps printer columns and scope`() {
        val crd = CustomResourceDefinitionBuilder()
            .withNewMetadata().withName("widgets.example.com").endMetadata()
            .withNewSpec()
            .withGroup("example.com")
            .withScope("Namespaced")
            .withNewNames()
            .withKind("Widget").withPlural("widgets").withSingular("widget")
            .endNames()
            .addNewVersion()
            .withName("v1beta1").withServed(true).withStorage(false)
            .endVersion()
            .addNewVersion()
            .withName("v1").withServed(true).withStorage(true)
            .addNewAdditionalPrinterColumn()
            .withName("Phase").withType("string").withJsonPath(".status.phase").withPriority(0)
            .endAdditionalPrinterColumn()
            .endVersion()
            .endSpec()
            .build()

        val info = ResourceMappers.mapCrd(crd)!!
        assertEquals("example.com", info.group)
        assertEquals("v1", info.version) // storage version wins over the served-only v1beta1
        assertEquals("Widget", info.kind)
        assertEquals(CrdScope.NAMESPACED, info.scope)
        assertEquals(1, info.columns.size)
        assertEquals("Phase", info.columns[0].name)
        assertEquals(".status.phase", info.columns[0].jsonPath)
    }

    @Test
    fun `mapCrd returns null when no version is served`() {
        val crd = CustomResourceDefinitionBuilder()
            .withNewMetadata().withName("things.example.com").endMetadata()
            .withNewSpec()
            .withGroup("example.com")
            .withScope("Cluster")
            .withNewNames().withKind("Thing").withPlural("things").endNames()
            .addNewVersion().withName("v1").withServed(false).withStorage(true).endVersion()
            .endSpec()
            .build()
        assertTrue(ResourceMappers.mapCrd(crd) == null)
    }

    @Test
    fun `mapCrd falls back to served version columns when storage version has none`() {
        val crd = CustomResourceDefinitionBuilder()
            .withNewMetadata().withName("gadgets.example.com").endMetadata()
            .withNewSpec()
            .withGroup("example.com")
            .withScope("Namespaced")
            .withNewNames()
            .withKind("Gadget").withPlural("gadgets").withSingular("gadget")
            .endNames()
            .addNewVersion()
            .withName("v1beta1").withServed(true).withStorage(false)
            .addNewAdditionalPrinterColumn()
            .withName("Phase").withType("string").withJsonPath(".status.phase").withPriority(0)
            .endAdditionalPrinterColumn()
            .endVersion()
            .addNewVersion()
            .withName("v1").withServed(true).withStorage(true)
            // no printer columns on this version
            .endVersion()
            .endSpec()
            .build()

        val info = ResourceMappers.mapCrd(crd)!!
        assertEquals("v1", info.version) // listing stays on storage version
        assertEquals(1, info.columns.size)
        assertEquals("Phase", info.columns[0].name)
    }
}
