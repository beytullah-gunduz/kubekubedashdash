package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerResources
import kotlin.test.Test
import kotlin.test.assertEquals

class DemoContainerResourcesTest {
    @Test
    fun `demo pods carry requests and limits the mapper reads back`() {
        val pod = MockClusterProvider.buildPod("web-0", "default", "web", "nginx:1.25", "mock-node-1", phase = "Running")

        assertEquals(
            listOf(ContainerResources("web", 50, 500, 128L shl 20, 1L shl 30)),
            ResourceMappers.mapPod(pod).resources,
        )
    }
}
