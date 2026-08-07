package com.kubekubedashdash.ui.screens.generic

import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.PodInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure-function tests for [resolveJobLogTarget] — the decision logic behind
 * [JobLogsDialog]'s "View logs" action on a Job.
 */
class JobLogsResolutionTest {

    private fun container(name: String) = ContainerInfo(
        name = name,
        image = "fake.example/app:latest",
        ready = true,
        restartCount = 0,
        state = "running",
    )

    private fun pod(name: String, vararg containers: String) = PodInfo(
        uid = "uid-$name",
        name = name,
        namespace = "demo",
        status = "Succeeded",
        ready = "1/1",
        restarts = 0,
        age = "5m",
        node = "node-a",
        ip = "10.0.0.1",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = containers.map(::container),
    )

    @Test
    fun `no pods yields NoPods`() {
        assertEquals(JobLogTarget.NoPods, resolveJobLogTarget(emptyList()))
    }

    @Test
    fun `two pods yields PickPod preserving the input list and order`() {
        val pods = listOf(pod("job-abc-1", "main"), pod("job-abc-2", "main"))

        val target = resolveJobLogTarget(pods)

        assertTrue(target is JobLogTarget.PickPod)
        assertSame(pods, target.pods)
    }

    @Test
    fun `single pod with a single container auto-opens with no container specified`() {
        val onlyPod = pod("job-abc-1", "main")

        val target = resolveJobLogTarget(listOf(onlyPod))

        assertEquals(JobLogTarget.AutoOpen(onlyPod, container = null), target)
    }

    @Test
    fun `single pod with multiple containers yields PickContainer`() {
        val onlyPod = pod("job-abc-1", "main", "sidecar")

        val target = resolveJobLogTarget(listOf(onlyPod))

        assertEquals(JobLogTarget.PickContainer(onlyPod), target)
    }
}
