package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [resyncSelection] and [toggleSelection] (retro TODO #21): selection identity is the uid when
 * both rows have one and namespace/name otherwise, so several blank-uid rows (synthetic data)
 * no longer collapse onto the first of them.
 */
class ResourceSelectionTest {

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private fun row(uid: String, name: String, namespace: String? = "ns-a", status: String = "Active") = GenericResourceInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        status = status,
        age = "1m",
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    @Test
    fun `resync keeps the second blank-uid row selected`() {
        val b = row("", "b")
        val a2 = row("", "a", status = "Updated")
        val b2 = row("", "b", status = "Updated")
        assertEquals(b2, resyncSelection(b, listOf(a2, b2)))
    }

    @Test
    fun `toggle selects the second blank-uid row instead of deselecting the first`() {
        val a = row("", "a")
        val b = row("", "b")
        assertEquals(b, toggleSelection(a, b))
    }

    @Test
    fun `toggle on the selected blank-uid row clears it`() {
        val b = row("", "b")
        assertNull(toggleSelection(b, b))
    }

    @Test
    fun `toggle with nothing selected selects the clicked row and toggle of null clears`() {
        val a = row("", "a")
        assertEquals(a, toggleSelection(null, a))
        assertNull(toggleSelection(a, null))
    }

    @Test
    fun `resync follows the uid when it is set, even after a rename`() {
        val before = row("u-1", "old-name")
        val renamed = row("u-1", "new-name")
        val other = row("u-2", "old-name")
        assertEquals(renamed, resyncSelection(before, listOf(other, renamed)))
    }

    @Test
    fun `toggle on a renamed row with the same uid is the same resource`() {
        val before = row("u-1", "old-name")
        val renamed = row("u-1", "new-name")
        assertNull(toggleSelection(before, renamed))
    }

    @Test
    fun `resync gives null when the selected uid left the list`() {
        val gone = row("u-1", "a")
        assertNull(resyncSelection(gone, listOf(row("u-2", "b"), row("u-3", "c"))))
    }

    @Test
    fun `resync gives null when a blank-uid row left the list`() {
        val gone = row("", "a")
        assertNull(resyncSelection(gone, listOf(row("", "b"))))
    }

    @Test
    fun `resync of nothing stays nothing`() {
        assertNull(resyncSelection(null, listOf(row("u-1", "a"))))
    }

    @Test
    fun `rows in different namespaces with blank uids are different resources`() {
        val a = row("", "web", namespace = "ns-a")
        val b = row("", "web", namespace = "ns-b")
        assertEquals(b, toggleSelection(a, b))
    }
}
