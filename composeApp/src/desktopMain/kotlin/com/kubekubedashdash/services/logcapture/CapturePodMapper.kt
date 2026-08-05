package com.kubekubedashdash.services.logcapture

import io.fabric8.kubernetes.api.model.ContainerStatus
import io.fabric8.kubernetes.api.model.Pod

/**
 * Enumerates init + main + ephemeral containers, which
 * [com.kubekubedashdash.util.ResourceMappers.mapPod] deliberately does not
 * (it maps only spec.containers).
 */
object CapturePodMapper {

    fun map(pod: Pod): CapturePodSpec {
        val spec = pod.spec
        val status = pod.status

        val initStatuses = status?.initContainerStatuses ?: emptyList()
        val mainStatuses = status?.containerStatuses ?: emptyList()
        val ephemeralStatuses = status?.ephemeralContainerStatuses ?: emptyList()

        val initContainers = spec?.initContainers.orEmpty().map { c ->
            toSpec(c.name, CaptureContainerKind.INIT, initStatuses)
        }
        val mainContainers = spec?.containers.orEmpty().map { c ->
            toSpec(c.name, CaptureContainerKind.MAIN, mainStatuses)
        }
        val ephemeralContainers = spec?.ephemeralContainers.orEmpty().map { c ->
            toSpec(c.name, CaptureContainerKind.EPHEMERAL, ephemeralStatuses)
        }

        return CapturePodSpec(
            name = pod.metadata?.name ?: "",
            phase = pod.status?.phase ?: "Unknown",
            containers = initContainers + mainContainers + ephemeralContainers,
        )
    }

    private fun toSpec(
        name: String,
        kind: CaptureContainerKind,
        statuses: List<ContainerStatus>,
    ): CaptureContainerSpec {
        val status = statuses.find { it.name == name }
        return CaptureContainerSpec(
            name = name,
            kind = kind,
            restartCount = status?.restartCount ?: 0,
            started = status?.state?.running != null || status?.state?.terminated != null,
        )
    }
}
