package com.kubekubedashdash.data.repository

import com.kubekubedashdash.util.DemoContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Every per-cluster preference store keys the demo cluster the same way.
 * `PreferenceRepository.defaultNamespaceByContext` already folds every minted
 * "demo-cluster (mock) #N" label to the one row the picker lists
 * ([DemoContext.preferenceKey]); favourites, recents and CRD pins/hides were
 * keyed by the raw label instead, so they vanished whenever the label was
 * re-minted — every fresh pick from the picker, and a restore of a `#N` that
 * no longer exists. The fold now lives in the repositories' pure update
 * functions, so no caller can get it wrong.
 */
class DemoContextPreferenceKeyTest {

    private val minted = "demo-cluster (mock) #3"
    private val reminted = "demo-cluster (mock) #7"
    private val folded = DemoContext.MOCK_CONTEXT_NAME

    @Test
    fun `favourites toggled under a minted demo label are stored under the demo key`() {
        val after = computeToggleFavourite(emptyMap(), minted, "Pods")
        assertEquals(listOf("Pods"), after[folded])
        assertFalse(minted in after, "the raw minted label must not become a key")
        val gone = computeToggleFavourite(after, reminted, "Pods")
        assertFalse(folded in gone, "a later mint must find and remove the same favourite")
    }

    @Test
    fun `recents recorded under a minted demo label are stored under the demo key`() {
        val after = computeRecordRecent(emptyMap(), minted, "Nodes")
        assertEquals(listOf("Nodes"), after[folded])
        assertFalse(minted in after)
        val removed = computeRemoveRecent(after, reminted, "Nodes")
        assertFalse(folded in removed)
    }

    @Test
    fun `CRD pins and hides under a minted demo label are stored under the demo key`() {
        val (pinned, hiddenAfterPin) = CrdPreferenceRepository.computeTogglePinned(emptyMap(), emptyMap(), minted, "g/Spark")
        assertEquals(setOf("g/Spark"), pinned[folded])
        assertFalse(minted in pinned)
        val (pinnedAfterHide, hidden) = CrdPreferenceRepository.computeToggleHidden(pinned, hiddenAfterPin, reminted, "g/Spark")
        assertEquals(setOf("g/Spark"), hidden[folded])
        assertFalse(folded in pinnedAfterHide, "hiding through a re-minted label must unpin the same key")
    }

    @Test
    fun `a real context is stored under its own name`() {
        assertEquals(listOf("Pods"), computeToggleFavourite(emptyMap(), "example-context", "Pods")["example-context"])
        val (pinned, _) = CrdPreferenceRepository.computeTogglePinned(emptyMap(), emptyMap(), "example-context", "g/Spark")
        assertEquals(setOf("g/Spark"), pinned["example-context"])
    }
}
