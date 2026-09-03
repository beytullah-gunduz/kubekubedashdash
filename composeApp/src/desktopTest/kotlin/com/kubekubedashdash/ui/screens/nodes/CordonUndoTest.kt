package com.kubekubedashdash.ui.screens.nodes

import com.kubekubedashdash.models.NodeInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the bulk cordon/uncordon undo: only the nodes the run actually changed
 * are flipped back, a run that changed nothing offers no undo, and a partial
 * undo failure is reported as one counted failure. Pure: the cordon call is a
 * recording function, no client or cluster involved.
 */
class CordonUndoTest {

    private fun node(name: String, unschedulable: Boolean) = NodeInfo(
        uid = "uid-$name",
        name = name,
        status = "Ready",
        roles = "worker",
        version = "v1.30.0",
        os = "linux",
        arch = "arm64",
        containerRuntime = "containerd",
        cpu = "4",
        memory = "8Gi",
        pods = "110",
        age = "1d",
        unschedulable = unschedulable,
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    @Test
    fun `undoing a bulk cordon flips back only the nodes that were schedulable before`() = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val snapshot = listOf(node("n1", unschedulable = false), node("n2", unschedulable = true))
        val undo = cordonUndo(snapshot, unschedulable = false) { name, unschedulable ->
            calls += name to unschedulable
            Result.success(Unit)
        }
        assertNotNull(undo)
        assertEquals("Uncordoned 1 Node", undo.successTitle)
        assertTrue(undo.run().isSuccess)
        assertEquals(listOf("n1" to false), calls, "the hand-cordoned node must not be touched")
    }

    @Test
    fun `undoing a bulk uncordon flips back only the nodes that were cordoned before`() = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val snapshot = listOf(node("n1", unschedulable = true), node("n2", unschedulable = true), node("n3", unschedulable = false))
        val undo = cordonUndo(snapshot, unschedulable = true) { name, unschedulable ->
            calls += name to unschedulable
            Result.success(Unit)
        }
        assertNotNull(undo)
        assertEquals("Cordoned 2 Nodes", undo.successTitle)
        assertTrue(undo.run().isSuccess)
        assertEquals(listOf("n1" to true, "n2" to true), calls)
    }

    @Test
    fun `a run that changed nothing offers no undo`() {
        val snapshot = listOf(node("n1", unschedulable = true), node("n2", unschedulable = true))
        assertNull(cordonUndo(snapshot, unschedulable = false) { _, _ -> Result.success(Unit) })
    }

    @Test
    fun `a partial undo failure is one counted failure`() = runBlocking {
        val snapshot = listOf(node("n1", unschedulable = false), node("n2", unschedulable = false))
        val undo = cordonUndo(snapshot, unschedulable = false) { name, _ ->
            if (name == "n2") Result.failure(IllegalStateException("forbidden")) else Result.success(Unit)
        }
        assertNotNull(undo)
        val result = undo.run()
        assertTrue(result.isFailure)
        assertEquals("1 of 2 nodes could not be uncordoned", result.exceptionOrNull()?.message)
        assertEquals("Undo failed: some nodes are still cordoned", undo.failureTitle)
    }
}
