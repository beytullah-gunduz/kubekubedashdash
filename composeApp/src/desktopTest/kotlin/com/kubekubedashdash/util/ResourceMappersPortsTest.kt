package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerPortInfo
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.ContainerPortBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for the port-forward model fields added to [ResourceMappers]:
 * `mapPod`'s new `ContainerInfo.ports`, and [ResourceMappers.mapServicePortSpecs].
 */
class ResourceMappersPortsTest {

    @Test
    fun `mapPod carries declared container ports, defaulting a null protocol to TCP`() {
        val pod = PodBuilder()
            .withNewMetadata().withName("web-0").endMetadata()
            .withNewSpec()
            .withContainers(
                ContainerBuilder()
                    .withName("app")
                    .withImage("app:1")
                    .withPorts(
                        ContainerPortBuilder().withName("http").withContainerPort(8080).build(),
                        ContainerPortBuilder().withContainerPort(9090).withProtocol("UDP").build(),
                    )
                    .build(),
            )
            .endSpec()
            .build()

        val info = ResourceMappers.mapPod(pod)

        assertEquals(
            listOf(
                ContainerPortInfo("http", 8080, "TCP"),
                ContainerPortInfo("", 9090, "UDP"),
            ),
            info.containers[0].ports,
        )
    }

    @Test
    fun `mapServicePortSpecs reads int, named and unset targetPort`() {
        val ports = listOf(
            ServicePortBuilder().withPort(80).withTargetPort(IntOrString(8080)).build(),
            ServicePortBuilder().withPort(443).withTargetPort(IntOrString("http")).build(),
            ServicePortBuilder().withPort(53).withProtocol("UDP").build(),
        )

        val specs = ResourceMappers.mapServicePortSpecs(ports)

        assertEquals("8080", specs[0].targetPort)
        assertEquals("http", specs[1].targetPort)
        assertEquals("", specs[2].targetPort)
    }
}
