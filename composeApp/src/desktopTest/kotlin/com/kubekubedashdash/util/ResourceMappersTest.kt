package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerTermination
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
        assertEquals("", info.containers[0].stateMessage)
        assertNull(info.containers[0].lastTermination)
    }

    @Test
    fun `mapPod carries the waiting message and the last termination`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("app-0").endMetadata()
            .withNewSpec()
            .withContainers(ContainerBuilder().withName("app").withImage("fake.example/app:latest").build())
            .endSpec()
            .withNewStatus()
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("app")
                    .withNewState()
                    .withNewWaiting()
                    .withReason("CrashLoopBackOff")
                    .withMessage("back-off 5m0s restarting failed container=app")
                    .endWaiting()
                    .endState()
                    .withNewLastState()
                    .withNewTerminated()
                    .withReason("OOMKilled")
                    .withExitCode(137)
                    .withFinishedAt("2026-01-01T00:05:00Z")
                    .endTerminated()
                    .endLastState()
                    .build(),
            )
            .endStatus()
            .build()

        val info = ResourceMappers.mapPod(pod)

        assertEquals("back-off 5m0s restarting failed container=app", info.containers[0].stateMessage)
        assertNull(info.containers[0].exitCode)
        assertEquals(
            ContainerTermination("OOMKilled", 137, "2026-01-01T00:05:00Z", ""),
            info.containers[0].lastTermination,
        )
    }

    @Test
    fun `mapPod carries a terminated container's exit code and message`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("app-0").endMetadata()
            .withNewSpec()
            .withContainers(ContainerBuilder().withName("app").withImage("fake.example/app:latest").build())
            .endSpec()
            .withNewStatus()
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("app")
                    .withNewState()
                    .withNewTerminated()
                    .withReason("Error")
                    .withExitCode(2)
                    .withMessage("boom")
                    .endTerminated()
                    .endState()
                    .build(),
            )
            .endStatus()
            .build()

        val info = ResourceMappers.mapPod(pod)

        assertEquals(2, info.containers[0].exitCode)
        assertEquals("boom", info.containers[0].stateMessage)
        assertNull(info.containers[0].lastTermination)
    }

    @Test
    fun `mapPod carries the pod-level reason and message`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("app-0").endMetadata()
            .withNewStatus()
            .withPhase("Failed")
            .withReason("Evicted")
            .withMessage("The node was low on resource: memory.")
            .endStatus()
            .build()

        val info = ResourceMappers.mapPod(pod)

        assertEquals("Evicted", info.statusReason)
        assertEquals("The node was low on resource: memory.", info.statusMessage)
    }

    @Test
    fun `mapPod keeps the PodScheduled message only while it is False`() {
        val pending = PodBuilder()
            .withNewMetadata().withName("app-0").endMetadata()
            .withNewStatus()
            .addNewCondition()
            .withType("PodScheduled")
            .withStatus("False")
            .withMessage("0/3 nodes are available: 3 Insufficient cpu.")
            .endCondition()
            .endStatus()
            .build()
        val scheduled = PodBuilder()
            .withNewMetadata().withName("app-1").endMetadata()
            .withNewStatus()
            .addNewCondition()
            .withType("PodScheduled")
            .withStatus("True")
            .withMessage("scheduled")
            .endCondition()
            .endStatus()
            .build()

        assertEquals(
            "0/3 nodes are available: 3 Insufficient cpu.",
            ResourceMappers.mapPod(pending).schedulingMessage,
        )
        assertEquals("", ResourceMappers.mapPod(scheduled).schedulingMessage)
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
                ObjectReferenceBuilder().withKind("Pod").withName("web-0").withUid("pod-uid-1").build(),
            )
            .withMessage("Back-off restarting failed container")
            .build()

        val info = ResourceMappers.mapEvent(ev)!!
        assertEquals("Pod/web-0", info.objectRef)
        assertEquals("Pod", info.objectKind)
        assertEquals("web-0", info.objectName)
        assertEquals("pod-uid-1", info.objectUid)
        assertEquals("Normal", info.type) // default when ev.type is null
        assertEquals(1, info.count) // default when ev.count is null
        assertEquals("kube-system", info.namespace)
    }

    @Test
    fun `mapEvent leaves objectUid blank when the involved object carries none`() {
        val ev = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("default").withUid("ev-2").endMetadata()
            .withLastTimestamp("2026-02-02T10:00:00Z")
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("web-0").build())
            .build()

        assertEquals("", ResourceMappers.mapEvent(ev)!!.objectUid)
    }

    @Test
    fun `mapEvent reads an events-v1 series for last seen and count`() {
        val ev = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("default").endMetadata()
            .withNewEventTime("2026-02-02T10:00:00.123456Z")
            .withNewSeries().withCount(7).withNewLastObservedTime("2026-02-02T10:05:00.654321Z").endSeries()
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("web-0").build())
            .build()

        val info = ResourceMappers.mapEvent(ev)!!
        assertEquals("2026-02-02T10:05:00Z", info.lastSeenTimestamp)
        assertEquals(7, info.count)
    }

    @Test
    fun `mapEvent falls back to eventTime for a first occurrence`() {
        val ev = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("default").withCreationTimestamp("2026-02-02T10:00:01Z").endMetadata()
            .withNewEventTime("2026-02-02T10:00:00.123456Z")
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("web-0").build())
            .build()

        val info = ResourceMappers.mapEvent(ev)!!
        assertEquals("2026-02-02T10:00:00Z", info.lastSeenTimestamp)
        assertEquals(1, info.count)
    }

    @Test
    fun `mapEvent prefers the series over the deprecated lastTimestamp`() {
        val ev = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("default").endMetadata()
            .withLastTimestamp("2026-02-02T09:00:00Z")
            .withCount(3)
            .withNewSeries().withCount(9).withNewLastObservedTime("2026-02-02T10:05:00Z").endSeries()
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("web-0").build())
            .build()

        val info = ResourceMappers.mapEvent(ev)!!
        assertEquals("2026-02-02T10:05:00Z", info.lastSeenTimestamp)
        assertEquals(9, info.count)
    }

    @Test
    fun `mapEvent keeps a legacy lastTimestamp when there is no series`() {
        val ev = EventBuilder()
            .withNewMetadata().withName("e").withNamespace("default").endMetadata()
            .withLastTimestamp("2026-02-02T09:00:00Z")
            .withNewEventTime("2026-02-02T08:00:00.000001Z")
            .withCount(4)
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("web-0").build())
            .build()

        val info = ResourceMappers.mapEvent(ev)!!
        assertEquals("2026-02-02T09:00:00Z", info.lastSeenTimestamp)
        assertEquals(4, info.count)
    }

    @Test
    fun `canonicalTimestamp truncates fractions and leaves junk alone`() {
        assertEquals("2026-02-02T10:00:00Z", ResourceMappers.canonicalTimestamp("2026-02-02T10:00:00.123456Z"))
        assertEquals("2026-02-02T10:00:00Z", ResourceMappers.canonicalTimestamp("2026-02-02T10:00:00Z"))
        assertEquals("", ResourceMappers.canonicalTimestamp(""))
        assertEquals("", ResourceMappers.canonicalTimestamp(null))
        assertEquals("not-a-time", ResourceMappers.canonicalTimestamp("not-a-time"))
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
