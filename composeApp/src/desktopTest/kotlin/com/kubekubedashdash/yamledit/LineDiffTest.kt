package com.kubekubedashdash.yamledit

import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LineDiffTest {

    private fun lines(vararg s: String) = s.toList()

    /** Equal and Insert rebuild [new]; Equal and Delete rebuild [old]; every index is used once, in order. */
    private fun assertReplays(ops: List<DiffOp>, old: List<String>, new: List<String>, message: String = "") {
        val rebuiltNew = ArrayList<String>()
        val rebuiltOld = ArrayList<String>()
        var nextOld = 0
        var nextNew = 0
        for (op in ops) {
            when (op) {
                is DiffOp.Equal -> {
                    assertEquals(nextOld++, op.oldIndex, message)
                    assertEquals(nextNew++, op.newIndex, message)
                    assertEquals(old[op.oldIndex], new[op.newIndex], message)
                    rebuiltOld += old[op.oldIndex]
                    rebuiltNew += new[op.newIndex]
                }

                is DiffOp.Delete -> {
                    assertEquals(nextOld++, op.oldIndex, message)
                    rebuiltOld += old[op.oldIndex]
                }

                is DiffOp.Insert -> {
                    assertEquals(nextNew++, op.newIndex, message)
                    rebuiltNew += new[op.newIndex]
                }
            }
        }
        assertEquals(new, rebuiltNew, message)
        assertEquals(old, rebuiltOld, message)
    }

    /** The length of the longest common subsequence, by the textbook table. */
    private fun lcs(a: List<String>, b: List<String>): Int {
        val t = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) {
            for (j in b.indices) {
                t[i + 1][j + 1] = if (a[i] == b[j]) t[i][j] + 1 else maxOf(t[i][j + 1], t[i + 1][j])
            }
        }
        return t[a.size][b.size]
    }

    private fun check(old: List<String>, new: List<String>): List<DiffOp> {
        val ops = LineDiff.diff(old, new)
        assertReplays(ops, old, new, "old=$old new=$new")
        return ops
    }

    @Test
    fun `identical inputs are all Equal`() {
        val a = lines("a", "b", "c")
        val ops = check(a, a)
        assertEquals(listOf(DiffOp.Equal(0, 0), DiffOp.Equal(1, 1), DiffOp.Equal(2, 2)), ops)
        assertEquals(0, LineDiff.changedLineCount(ops))
        assertEquals(emptyList(), LineDiff.hunks(ops))
    }

    @Test
    fun `a single changed line is one Delete and one Insert`() {
        val ops = check(lines("a", "b", "c"), lines("a", "x", "c"))
        assertEquals(listOf(DiffOp.Equal(0, 0), DiffOp.Delete(1), DiffOp.Insert(1), DiffOp.Equal(2, 2)), ops)
        assertEquals(2, LineDiff.changedLineCount(ops))
    }

    @Test
    fun `an insert at the start and at the end`() {
        assertEquals(listOf(DiffOp.Insert(0), DiffOp.Equal(0, 1), DiffOp.Equal(1, 2)), check(lines("a", "b"), lines("x", "a", "b")))
        assertEquals(listOf(DiffOp.Equal(0, 0), DiffOp.Equal(1, 1), DiffOp.Insert(2)), check(lines("a", "b"), lines("a", "b", "x")))
    }

    @Test
    fun `a delete at the start and at the end`() {
        assertEquals(listOf(DiffOp.Delete(0), DiffOp.Equal(1, 0), DiffOp.Equal(2, 1)), check(lines("x", "a", "b"), lines("a", "b")))
        assertEquals(listOf(DiffOp.Equal(0, 0), DiffOp.Equal(1, 1), DiffOp.Delete(2)), check(lines("a", "b", "x"), lines("a", "b")))
    }

    @Test
    fun `everything replaced`() {
        val ops = check(lines("a", "b", "c"), lines("x", "y"))
        assertEquals(5, LineDiff.changedLineCount(ops))
        assertTrue(ops.none { it is DiffOp.Equal })
    }

    @Test
    fun `empty old, empty new and both empty`() {
        assertEquals(listOf(DiffOp.Insert(0), DiffOp.Insert(1)), check(emptyList(), lines("a", "b")))
        assertEquals(listOf(DiffOp.Delete(0), DiffOp.Delete(1)), check(lines("a", "b"), emptyList()))
        assertEquals(emptyList(), check(emptyList(), emptyList()))
    }

    @Test
    fun `a moved block is a delete and an insert`() {
        val ops = check(lines("a", "b", "c", "d", "e"), lines("d", "e", "a", "b", "c"))
        assertEquals(4, LineDiff.changedLineCount(ops))
    }

    @Test
    fun `repeated lines still diff minimally`() {
        val ops = check(lines("x", "x", "x", "x"), lines("x", "x", "y", "x", "x"))
        assertEquals(1, LineDiff.changedLineCount(ops))
        val ops2 = check(lines("a", "b", "a", "b", "a", "b"), lines("b", "a", "b", "a", "b", "a"))
        assertEquals(2, LineDiff.changedLineCount(ops2))
    }

    @Test
    fun `200 random pairs replay both inputs and are minimal`() {
        val random = Random(42)
        repeat(200) { round ->
            val alphabet = 2 + random.nextInt(6)
            val old = List(random.nextInt(0, 41)) { "l${random.nextInt(alphabet)}" }
            val new = if (round % 2 == 0) {
                List(random.nextInt(0, 41)) { "l${random.nextInt(alphabet)}" }
            } else {
                // A mutated copy: the realistic case of a few edits in a long file.
                old.toMutableList().also { copy ->
                    repeat(random.nextInt(0, 5)) {
                        when (random.nextInt(3)) {
                            0 -> if (copy.isNotEmpty()) copy.removeAt(random.nextInt(copy.size))
                            1 -> copy.add(random.nextInt(copy.size + 1), "n${random.nextInt(3)}")
                            else -> if (copy.isNotEmpty()) copy[random.nextInt(copy.size)] = "m${random.nextInt(3)}"
                        }
                    }
                }
            }
            val ops = LineDiff.diff(old, new)
            assertReplays(ops, old, new, "round $round")
            assertEquals(old.size + new.size - 2 * lcs(old, new), LineDiff.changedLineCount(ops), "not minimal in round $round")
        }
    }

    @Test
    fun `the edit cap falls back to one block and still replays`() {
        val random = Random(7)
        repeat(50) { round ->
            val old = List(random.nextInt(0, 30)) { "l${random.nextInt(4)}" }
            val new = List(random.nextInt(0, 30)) { "l${random.nextInt(4)}" }
            for (cap in listOf(0, 2)) {
                assertReplays(LineDiff.diff(old, new, maxEdits = cap), old, new, "round $round cap $cap")
            }
        }
    }

    @Test
    fun `beyond the cap the middle is all deletes then all inserts, around the trimmed ends`() {
        val old = lines("keep", "a", "b", "c", "keep2")
        val new = lines("keep", "x", "y", "z", "keep2")
        val capped = LineDiff.diff(old, new, maxEdits = 2)
        assertEquals(
            listOf(
                DiffOp.Equal(0, 0),
                DiffOp.Delete(1),
                DiffOp.Delete(2),
                DiffOp.Delete(3),
                DiffOp.Insert(1),
                DiffOp.Insert(2),
                DiffOp.Insert(3),
                DiffOp.Equal(4, 4),
            ),
            capped,
        )
        // Within the cap the same input gives the same minimal answer (six edits).
        assertEquals(6, LineDiff.changedLineCount(LineDiff.diff(old, new, maxEdits = 6)))
    }

    @Test
    fun `hunks carry three lines of context and merge windows that touch`() {
        val old = (1..30).map { "line $it" }
        val new = old.toMutableList().also {
            it[9] = "changed 10"
            it[15] = "changed 16"
        }
        // 5 untouched lines between the edits: the two 3-line windows overlap, one hunk.
        val one = LineDiff.hunks(LineDiff.diff(old, new))
        assertEquals(1, one.size)
        assertEquals(3 + 2 + 5 + 2 + 3, one[0].ops.size)
        assertEquals(DiffOp.Equal(6, 6), one[0].ops.first())
        assertEquals(DiffOp.Equal(18, 18), one[0].ops.last())
    }

    @Test
    fun `hunks split when the gap is wider than twice the context`() {
        val old = (1..40).map { "line $it" }
        val new = old.toMutableList().also {
            it[5] = "changed 6"
            it[30] = "changed 31"
        }
        val hunks = LineDiff.hunks(LineDiff.diff(old, new))
        assertEquals(2, hunks.size)
        assertEquals(3 + 2 + 3, hunks[0].ops.size)
        assertEquals(3 + 2 + 3, hunks[1].ops.size)
        assertEquals(DiffOp.Equal(2, 2), hunks[0].ops.first())
        assertEquals(DiffOp.Equal(27, 27), hunks[1].ops.first())
        assertIs<DiffOp.Equal>(hunks[0].ops.last())
    }

    @Test
    fun `hunks at the edges of the file are clipped and a custom context is honoured`() {
        val old = lines("a", "b", "c", "d", "e")
        val new = lines("A", "b", "c", "d", "E")
        val ops = LineDiff.diff(old, new)
        val wide = LineDiff.hunks(ops)
        assertEquals(1, wide.size)
        assertEquals(ops, wide[0].ops)
        val narrow = LineDiff.hunks(ops, context = 0)
        assertEquals(2, narrow.size)
        assertEquals(listOf(DiffOp.Delete(0), DiffOp.Insert(0)), narrow[0].ops)
        assertEquals(listOf(DiffOp.Delete(4), DiffOp.Insert(4)), narrow[1].ops)
    }

    @Test
    fun `20000 lines with three edits finish well inside a generous ceiling`() {
        val old = (0 until 20_000).map { "key$it: value $it" }
        val new = old.toMutableList().also {
            it[10] = "changed 10"
            it.removeAt(10_000)
            it.add(15_000, "inserted line")
        }
        var ops: List<DiffOp> = emptyList()
        val elapsed = measureTimeMillis { ops = LineDiff.diff(old, new) }
        assertReplays(ops, old, new)
        assertEquals(4, LineDiff.changedLineCount(ops))
        assertTrue(elapsed < 5_000, "took $elapsed ms")
        assertEquals(3, LineDiff.hunks(ops).size)
    }
}
