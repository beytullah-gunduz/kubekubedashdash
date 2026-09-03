package com.kubekubedashdash.ui.feedback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the toast queue: expiry, the visible cap, dismiss, and the undo cycle.
 * Everything runs on the test scheduler's virtual clock; the state's io
 * dispatcher shares that scheduler, so the undo's off-EDT hop is deterministic.
 * Touches no cluster, client or user state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionFeedbackStateTest {

    private fun TestScope.newState() = ActionFeedbackState(backgroundScope, io = StandardTestDispatcher(testScheduler))

    private fun undo(run: suspend () -> Result<Unit>) = UndoAction(successTitle = "Undone", failureTitle = "Undo failed", run = run)

    @Test
    fun `a success toast expires after its duration`() = runTest {
        val state = newState()
        state.success("Cordoned node \"n1\"")
        advanceTimeBy(ActionFeedbackState.SUCCESS_MS - 1)
        runCurrent()
        assertEquals(1, state.toasts.value.size)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(state.toasts.value.isEmpty())
    }

    @Test
    fun `an undoable success outlives the plain success duration`() = runTest {
        val state = newState()
        state.success("Scaled", undo = undo { Result.success(Unit) })
        advanceTimeBy(ActionFeedbackState.SUCCESS_MS + 1)
        runCurrent()
        assertEquals(1, state.toasts.value.size, "an undoable toast must outlive the plain success duration")
        advanceTimeBy(ActionFeedbackState.UNDOABLE_MS)
        runCurrent()
        assertTrue(state.toasts.value.isEmpty())
    }

    @Test
    fun `dismiss removes a toast at once`() = runTest {
        val state = newState()
        val id = state.failure("Delete failed", detail = "forbidden")
        state.dismiss(id)
        assertTrue(state.toasts.value.isEmpty())
        advanceTimeBy(ActionFeedbackState.FAILURE_MS + 1)
        runCurrent()
        assertTrue(state.toasts.value.isEmpty())
    }

    @Test
    fun `the stack keeps the newest MAX_VISIBLE toasts`() = runTest {
        val state = newState()
        val ids = (1..ActionFeedbackState.MAX_VISIBLE + 2).map { state.info("toast $it") }
        assertEquals(ids.takeLast(ActionFeedbackState.MAX_VISIBLE), state.toasts.value.map { it.id })
    }

    @Test
    fun `undo runs the inverse and replaces the toast with its success title`() = runTest {
        val state = newState()
        var runs = 0
        val id = state.success(
            "Cordoned node \"n1\"",
            undo = UndoAction("Uncordoned node \"n1\"", "Undo failed") {
                runs++
                Result.success(Unit)
            },
        )
        state.undo(id)
        assertTrue(state.toasts.value.single().undoInFlight)
        runCurrent()
        assertEquals(1, runs)
        val replacement = state.toasts.value.single()
        assertEquals("Uncordoned node \"n1\"", replacement.title)
        assertEquals(ToastKind.Success, replacement.kind)
        assertNull(replacement.undo, "an undo result must not offer another undo")
        assertNotEquals(id, replacement.id)
    }

    @Test
    fun `a failing undo reports the failure title and the reason`() = runTest {
        val state = newState()
        val id = state.success(
            "Scaled",
            undo = UndoAction("Restored", "Undo failed: still 5 replicas") { Result.failure(IllegalStateException("forbidden")) },
        )
        state.undo(id)
        runCurrent()
        val replacement = state.toasts.value.single()
        assertEquals(ToastKind.Failure, replacement.kind)
        assertEquals("Undo failed: still 5 replicas", replacement.title)
        assertEquals("forbidden", replacement.detail)
    }

    @Test
    fun `a throwing undo degrades to a failure toast`() = runTest {
        val state = newState()
        val id = state.success("Scaled", undo = undo { error("boom") })
        state.undo(id)
        runCurrent()
        val replacement = state.toasts.value.single()
        assertEquals(ToastKind.Failure, replacement.kind)
        assertEquals("boom", replacement.detail)
    }

    @Test
    fun `undo is ignored while one is in flight and the toast does not expire meanwhile`() = runTest {
        val state = newState()
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val id = state.success(
            "Cordoned",
            undo = undo {
                runs++
                gate.await()
                Result.success(Unit)
            },
        )
        state.undo(id)
        state.undo(id)
        advanceTimeBy(ActionFeedbackState.UNDOABLE_MS + 1)
        runCurrent()
        assertEquals(1, state.toasts.value.size, "an undoing toast must not expire")
        assertTrue(state.toasts.value.single().undoInFlight)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, runs)
        assertEquals("Undone", state.toasts.value.single().title)
    }

    @Test
    fun `cap eviction skips a toast that is mid-undo`() = runTest {
        val state = newState()
        val gate = CompletableDeferred<Unit>()
        val undoing = state.success(
            "first",
            undo = undo {
                gate.await()
                Result.success(Unit)
            },
        )
        state.undo(undoing)
        repeat(ActionFeedbackState.MAX_VISIBLE) { state.info("later $it") }
        assertTrue(state.toasts.value.any { it.id == undoing }, "the undoing toast must survive the cap")
        assertEquals(ActionFeedbackState.MAX_VISIBLE, state.toasts.value.size)
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `a new toast is never the cap victim when every older toast is undoing`() = runTest {
        val state = newState()
        val gate = CompletableDeferred<Unit>()
        repeat(ActionFeedbackState.MAX_VISIBLE) { i ->
            val id = state.success(
                "undoing $i",
                undo = undo {
                    gate.await()
                    Result.success(Unit)
                },
            )
            state.undo(id)
        }
        val newest = state.info("newest")
        assertTrue(state.toasts.value.any { it.id == newest }, "the newest toast must be shown, not evicted")
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `undo on a toast without an inverse is a no-op`() = runTest {
        val state = newState()
        val id = state.success("Deleted pod")
        state.undo(id)
        runCurrent()
        assertEquals(1, state.toasts.value.size)
        assertFalse(state.toasts.value.single().undoInFlight)
    }

    @Test
    fun `clear drops every toast`() = runTest {
        val state = newState()
        state.success("a")
        state.failure("b")
        state.clear()
        assertTrue(state.toasts.value.isEmpty())
        advanceTimeBy(ActionFeedbackState.FAILURE_MS + 1)
        runCurrent()
        assertTrue(state.toasts.value.isEmpty())
    }
}
