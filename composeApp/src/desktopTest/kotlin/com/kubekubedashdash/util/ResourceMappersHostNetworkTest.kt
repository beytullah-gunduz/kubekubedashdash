package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/** `PodInfo.hostNetwork` reads `spec.hostNetwork`; a pod that does not set it is not on the host network. */
class ResourceMappersHostNetworkTest {

    private fun podWith(hostNetwork: Boolean?) = PodBuilder()
        .withNewMetadata().withName("web-0").endMetadata()
        .withNewSpec()
        .withHostNetwork(hostNetwork)
        .withContainers(ContainerBuilder().withName("app").build())
        .endSpec()
        .build()

    @Test
    fun `a pod on the host network is marked`() {
        assertEquals(true, ResourceMappers.mapPod(podWith(true)).hostNetwork)
    }

    @Test
    fun `a pod that sets hostNetwork to false or leaves it unset is not marked`() {
        assertEquals(false, ResourceMappers.mapPod(podWith(false)).hostNetwork)
        assertEquals(false, ResourceMappers.mapPod(podWith(null)).hostNetwork)
    }
}
