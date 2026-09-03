package com.kubekubedashdash.ui.palette

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the D8 verb catalog: every verb has a target, ids and labels are unique, and target lookup matches the table. */
class PaletteVerbsTest {

    @Test
    fun `every catalog verb has at least one target`() {
        assertTrue(PALETTE_VERBS.isNotEmpty())
        PALETTE_VERBS.forEach { verb ->
            assertTrue(verb.targets.isNotEmpty(), "verb '${verb.id}' has no targets")
        }
    }

    @Test
    fun `every catalog verb id is unique`() {
        val ids = PALETTE_VERBS.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `every catalog verb label is unique`() {
        val labels = PALETTE_VERBS.map { it.label }
        assertEquals(labels.distinct(), labels)
    }

    @Test
    fun `verbsForTarget resolves an icon for every catalog verb`() {
        // paletteVerbIcon throws on an unknown id — this fails loudly if the
        // icon table (VERB_ICONS) ever drifts from PALETTE_VERBS.
        PALETTE_VERBS.forEach { verb -> paletteVerbIcon(verb.id) }
    }

    @Test
    fun `verbsForTarget NODE is cordon, drain and delete, in catalog order`() {
        val ids = verbsForTarget(VerbTarget.NODE).map { it.id }
        assertEquals(listOf("cordon", "drain", "delete"), ids)
    }

    @Test
    fun `verbsForTarget POD does not contain cordon`() {
        val ids = verbsForTarget(VerbTarget.POD).map { it.id }
        assertFalse("cordon" in ids)
        assertEquals(listOf("evict", "forceDelete", "delete"), ids)
    }

    @Test
    fun `verbsForTarget CRONJOB is trigger, suspend and delete`() {
        val ids = verbsForTarget(VerbTarget.CRONJOB).map { it.id }
        assertEquals(listOf("trigger", "suspend", "delete"), ids)
    }

    @Test
    fun `scale targets deployment, statefulset and replicaset but not daemonset`() {
        val scale = PALETTE_VERBS.first { it.id == "scale" }
        assertEquals(
            setOf(VerbTarget.DEPLOYMENT, VerbTarget.STATEFULSET, VerbTarget.REPLICASET),
            scale.targets,
        )
    }

    @Test
    fun `delete targets every VerbTarget`() {
        val delete = PALETTE_VERBS.first { it.id == "delete" }
        assertEquals(VerbTarget.entries.toSet(), delete.targets)
    }

    @Test
    fun `targetKindLabel matches the ClusterActions kind string for every target`() {
        assertEquals("Pod", targetKindLabel(VerbTarget.POD))
        assertEquals("Node", targetKindLabel(VerbTarget.NODE))
        assertEquals("Deployment", targetKindLabel(VerbTarget.DEPLOYMENT))
        assertEquals("StatefulSet", targetKindLabel(VerbTarget.STATEFULSET))
        assertEquals("DaemonSet", targetKindLabel(VerbTarget.DAEMONSET))
        assertEquals("ReplicaSet", targetKindLabel(VerbTarget.REPLICASET))
        assertEquals("CronJob", targetKindLabel(VerbTarget.CRONJOB))
    }
}
