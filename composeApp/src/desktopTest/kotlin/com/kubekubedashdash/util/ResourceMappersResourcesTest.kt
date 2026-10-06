package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerResources
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for [ResourceMappers.containerResources] / `PodInfo.resources`: the
 * requests and limits of the containers that run for the pod's lifetime.
 */
class ResourceMappersResourcesTest {

    @Test
    fun `containers and native sidecars are read, plain init containers are not`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("web-0").endMetadata()
            .withNewSpec()
            .withContainers(
                ContainerBuilder()
                    .withName("app")
                    .withResources(
                        ResourceRequirementsBuilder()
                            .addToRequests("cpu", Quantity("250m"))
                            .addToRequests("memory", Quantity("256Mi"))
                            .addToLimits("cpu", Quantity("1"))
                            .addToLimits("memory", Quantity("1Gi"))
                            .build(),
                    )
                    .build(),
            )
            .withInitContainers(
                ContainerBuilder()
                    .withName("sidecar")
                    .withRestartPolicy("Always")
                    .withResources(ResourceRequirementsBuilder().addToLimits("memory", Quantity("64Mi")).build())
                    .build(),
                ContainerBuilder()
                    .withName("migrate")
                    .withResources(ResourceRequirementsBuilder().addToLimits("memory", Quantity("2Gi")).build())
                    .build(),
            )
            .endSpec()
            .build()

        val info = ResourceMappers.mapPod(pod)

        assertEquals(
            listOf(
                ContainerResources("app", 250, 1000, 256L shl 20, 1L shl 30),
                ContainerResources("sidecar", memoryLimitBytes = 64L shl 20),
            ),
            info.resources,
        )
    }

    @Test
    fun `a container without resources reads as all unset, and a zero quantity as unset`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("web-0").endMetadata()
            .withNewSpec()
            .withContainers(
                ContainerBuilder().withName("app").build(),
                ContainerBuilder()
                    .withName("side")
                    .withResources(ResourceRequirementsBuilder().addToLimits("cpu", Quantity("0")).build())
                    .build(),
            )
            .endSpec()
            .build()

        val info = ResourceMappers.mapPod(pod)

        assertEquals(listOf(ContainerResources("app"), ContainerResources("side")), info.resources)
    }
}
