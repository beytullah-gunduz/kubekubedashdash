package com.kubekubedashdash.ui.screens.pods

import com.kubekubedashdash.models.PodInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PodSelectionMenuTest {
    private fun pod(uid: String) = PodInfo(
        uid = uid,
        name = "pod-$uid",
        namespace = "example-ns",
        status = "Running",
        ready = "1/1",
        restarts = 0,
        age = "1m",
        node = "node-a",
        ip = "",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = emptyList(),
    )

    @Test
    fun headerNamesTheCount() {
        assertEquals("3 Pods selected", podSelectionHeader(3))
    }

    @Test
    fun labelsCarryTheScopeAndDestructiveIsLast() {
        assertEquals(
            listOf("Tail logs of 3 Pods", "Evict 3 Pods…", "Delete 3 Pods…"),
            podSelectionMenu(3) {}.map { it.label },
        )
    }

    @Test
    fun eachItemRunsItsActionExactlyOnce() {
        val items = podSelectionMenu(3) {}
        val expected = listOf(PodBulkAction.Tail, PodBulkAction.Evict, PodBulkAction.Delete)
        items.forEachIndexed { i, item ->
            val ran = mutableListOf<PodBulkAction>()
            podSelectionMenu(3) { ran += it }[i].onSelect()
            assertEquals(listOf(expected[i]), ran)
            assertEquals(item.label, podSelectionMenu(3) {}[i].label)
        }
    }

    @Test
    fun snapshotDropsStalePods() {
        val filtered = listOf(pod("a"), pod("b"), pod("c"))
        assertEquals(listOf("a", "c"), podBulkSnapshot(filtered, setOf("b"), setOf("a", "b", "c")).map { it.uid })
    }

    @Test
    fun snapshotDropsIdsNoLongerListed() {
        val filtered = listOf(pod("a"), pod("b"))
        assertEquals(listOf("a"), podBulkSnapshot(filtered, emptySet(), setOf("a", "gone")).map { it.uid })
    }

    @Test
    fun snapshotFollowsOnScreenOrderNotIdOrder() {
        val filtered = listOf(pod("a"), pod("b"), pod("c"))
        assertEquals(listOf("a", "b", "c"), podBulkSnapshot(filtered, emptySet(), setOf("c", "a", "b")).map { it.uid })
    }

    @Test
    fun snapshotIsEmptyWhenEverythingIsGone() {
        assertTrue(podBulkSnapshot(listOf(pod("a")), setOf("a"), setOf("a", "x")).isEmpty())
    }
}
