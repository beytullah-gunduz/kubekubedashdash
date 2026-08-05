package com.kubekubedashdash.services.logcapture

import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.api.model.EphemeralContainerBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapturePodMapperTest {

    @Test
    fun `maps init main and ephemeral containers with correct kinds`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("pod-a").endMetadata()
            .withNewSpec()
            .withInitContainers(ContainerBuilder().withName("init-db").build())
            .withContainers(ContainerBuilder().withName("app").build())
            .withEphemeralContainers(EphemeralContainerBuilder().withName("debugger").build())
            .endSpec()
            .withNewStatus()
            .withPhase("Running")
            .endStatus()
            .build()

        val spec = CapturePodMapper.map(pod)

        assertEquals("pod-a", spec.name)
        assertEquals("Running", spec.phase)
        assertEquals(3, spec.containers.size)
        assertEquals(CaptureContainerKind.INIT, spec.containers.single { it.name == "init-db" }.kind)
        assertEquals(CaptureContainerKind.MAIN, spec.containers.single { it.name == "app" }.kind)
        assertEquals(CaptureContainerKind.EPHEMERAL, spec.containers.single { it.name == "debugger" }.kind)
    }

    @Test
    fun `joins restart counts from the matching status list`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("pod-a").endMetadata()
            .withNewSpec()
            .withContainers(
                ContainerBuilder().withName("app").build(),
                ContainerBuilder().withName("sidecar").build(),
            )
            .endSpec()
            .withNewStatus()
            .withPhase("Running")
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("app")
                    .withRestartCount(3)
                    .withNewState().withNewRunning().endRunning().endState()
                    .build(),
                ContainerStatusBuilder()
                    .withName("sidecar")
                    .withRestartCount(0)
                    .withNewState().withNewRunning().endRunning().endState()
                    .build(),
            )
            .endStatus()
            .build()

        val spec = CapturePodMapper.map(pod)

        assertEquals(3, spec.containers.single { it.name == "app" }.restartCount)
        assertEquals(0, spec.containers.single { it.name == "sidecar" }.restartCount)
    }

    @Test
    fun `marks a waiting never-started container as not started`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("pod-a").endMetadata()
            .withNewSpec()
            .withContainers(ContainerBuilder().withName("app").build())
            .endSpec()
            .withNewStatus()
            .withPhase("Pending")
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("app")
                    .withRestartCount(0)
                    .withNewState().withNewWaiting().withReason("ContainerCreating").endWaiting().endState()
                    .build(),
            )
            .endStatus()
            .build()

        val spec = CapturePodMapper.map(pod)

        assertFalse(spec.containers.single { it.name == "app" }.started)
    }

    @Test
    fun `tolerates a pod with no status and no container statuses`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("pod-a").endMetadata()
            .withNewSpec()
            .withContainers(ContainerBuilder().withName("app").build())
            .endSpec()
            .build()

        val spec = CapturePodMapper.map(pod)

        assertEquals("Unknown", spec.phase)
        assertEquals(1, spec.containers.size)
        val container = spec.containers.single()
        assertEquals("app", container.name)
        assertEquals(0, container.restartCount)
        assertFalse(container.started)
    }

    @Test
    fun `tolerates a container present in spec but missing from status`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("pod-a").endMetadata()
            .withNewSpec()
            .withContainers(
                ContainerBuilder().withName("app").build(),
                ContainerBuilder().withName("no-status").build(),
            )
            .endSpec()
            .withNewStatus()
            .withPhase("Running")
            .withContainerStatuses(
                ContainerStatusBuilder()
                    .withName("app")
                    .withRestartCount(1)
                    .withNewState().withNewRunning().endRunning().endState()
                    .build(),
            )
            .endStatus()
            .build()

        val spec = CapturePodMapper.map(pod)

        val missing = spec.containers.single { it.name == "no-status" }
        assertEquals(0, missing.restartCount)
        assertFalse(missing.started)
        assertTrue(spec.containers.any { it.name == "app" && it.started })
    }
}
