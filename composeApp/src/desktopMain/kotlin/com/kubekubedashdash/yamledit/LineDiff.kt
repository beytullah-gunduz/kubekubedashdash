package com.kubekubedashdash.yamledit

/** One step of a line diff, in document order; indexes point into the old and new line lists. */
sealed interface DiffOp {
    data class Equal(val oldIndex: Int, val newIndex: Int) : DiffOp

    data class Delete(val oldIndex: Int) : DiffOp

    data class Insert(val newIndex: Int) : DiffOp
}

/** A run of ops with up to [LineDiff.CONTEXT] Equal lines around each change. */
data class DiffHunk(val ops: List<DiffOp>)

/**
 * A line diff for the review step (D9), with no dependency: the common prefix and suffix are trimmed
 * first, the middle goes through Myers' O(ND) greedy search with a backtracked trace, and an edit
 * distance beyond a cap degrades to one delete-then-insert block instead of an unbounded search.
 *
 * Replaying the ops rebuilds both inputs: Equal and Insert take `new[newIndex]`, Equal and Delete
 * take `old[oldIndex]`.
 */
object LineDiff {
    const val CONTEXT = 3
    const val MAX_EDITS = 4_000

    /** Marks a diagonal no path has reached. Far below any real x so `+ 1` cannot wrap. */
    private const val UNREACHED = Int.MIN_VALUE / 2

    /** The ops that turn [old] into [new], in document order. Beyond [maxEdits] changed lines the middle is one block. */
    fun diff(old: List<String>, new: List<String>, maxEdits: Int = MAX_EDITS): List<DiffOp> {
        val prefix = commonPrefix(old, new)
        val suffix = commonSuffix(old, new, prefix)
        val oldEnd = old.size - suffix
        val newEnd = new.size - suffix
        val ops = ArrayList<DiffOp>(old.size + 8)
        for (i in 0 until prefix) ops += DiffOp.Equal(i, i)
        middle(old, prefix, oldEnd, new, prefix, newEnd, maxEdits, ops)
        for (i in 0 until suffix) ops += DiffOp.Equal(oldEnd + i, newEnd + i)
        return ops
    }

    /** [ops] cut into hunks: every change with [context] ops around it, windows that touch merged. */
    fun hunks(ops: List<DiffOp>, context: Int = CONTEXT): List<DiffHunk> {
        val hunks = ArrayList<DiffHunk>()
        var start = -1
        var end = -1
        for (i in ops.indices) {
            if (ops[i] is DiffOp.Equal) continue
            val from = maxOf(0, i - context)
            val to = minOf(ops.size - 1, i + context)
            if (start >= 0 && from <= end + 1) {
                end = maxOf(end, to)
            } else {
                if (start >= 0) hunks += DiffHunk(ops.subList(start, end + 1).toList())
                start = from
                end = to
            }
        }
        if (start >= 0) hunks += DiffHunk(ops.subList(start, end + 1).toList())
        return hunks
    }

    /** The number of Delete and Insert ops in [ops]. */
    fun changedLineCount(ops: List<DiffOp>): Int = ops.count { it !is DiffOp.Equal }

    private fun commonPrefix(old: List<String>, new: List<String>): Int {
        val limit = minOf(old.size, new.size)
        var n = 0
        while (n < limit && old[n] == new[n]) n++
        return n
    }

    private fun commonSuffix(old: List<String>, new: List<String>, prefix: Int): Int {
        val limit = minOf(old.size, new.size) - prefix
        var n = 0
        while (n < limit && old[old.size - 1 - n] == new[new.size - 1 - n]) n++
        return n
    }

    /** Appends the ops for `old[oldFrom until oldTo]` against `new[newFrom until newTo]`; neither range starts or ends with a common line. */
    private fun middle(
        old: List<String>,
        oldFrom: Int,
        oldTo: Int,
        new: List<String>,
        newFrom: Int,
        newTo: Int,
        maxEdits: Int,
        out: MutableList<DiffOp>,
    ) {
        val n = oldTo - oldFrom
        val m = newTo - newFrom
        if (n == 0 || m == 0) {
            replaceBlock(oldFrom, oldTo, newFrom, newTo, out)
            return
        }
        val maxD = minOf(n + m, maxEdits.coerceAtLeast(0))
        val offset = maxD + 1
        // furthest[offset + k] = the furthest x reached on diagonal k (x - y = k) in the latest round
        // that touched it. A round d only writes diagonals of parity d, so the neighbours k +- 1 a
        // round reads still hold the previous round's values.
        val furthest = IntArray(2 * maxD + 3) { UNREACHED }
        furthest[offset + 1] = 0
        // trace[d][j] = furthest[k = -d + 2j] after round d, kept for the backtrack.
        val trace = ArrayList<IntArray>()
        var found = -1
        search@ for (d in 0..maxD) {
            var k = maxOf(-d, -m)
            if (((k + d) and 1) != 0) k++
            val last = minOf(d, n)
            while (k <= last) {
                val left = furthest[offset + k - 1]
                val up = furthest[offset + k + 1]
                val step = chooseStep(left, up, k, n, m)
                if (step == STEP_NONE) {
                    furthest[offset + k] = UNREACHED
                } else {
                    var x = if (step == STEP_INSERT) up else left + 1
                    var y = x - k
                    while (x < n && y < m && old[oldFrom + x] == new[newFrom + y]) {
                        x++
                        y++
                    }
                    furthest[offset + k] = x
                    if (x == n && y == m) {
                        found = d
                        break@search
                    }
                }
                k += 2
            }
            trace += IntArray(d + 1) { j -> furthest[offset - d + 2 * j] }
        }
        if (found < 0) {
            replaceBlock(oldFrom, oldTo, newFrom, newTo, out)
            return
        }
        backtrack(trace, found, n, m, oldFrom, newFrom, out)
    }

    /**
     * Which neighbour round `d` extends diagonal [k] from: [STEP_INSERT] (down, from k + 1, consumes
     * a new line), [STEP_DELETE] (right, from k - 1, consumes an old line) or [STEP_NONE]. A
     * neighbour that already sits on the grid's edge cannot be extended. Ties go to the insert, as in
     * Myers' paper (`V[k - 1] < V[k + 1]`).
     */
    private fun chooseStep(left: Int, up: Int, k: Int, n: Int, m: Int): Int {
        val canDelete = left >= 0 && left < n
        val canInsert = up >= 0 && up - (k + 1) < m
        return when {
            !canDelete && !canInsert -> STEP_NONE
            !canDelete -> STEP_INSERT
            !canInsert -> STEP_DELETE
            left < up -> STEP_INSERT
            else -> STEP_DELETE
        }
    }

    private fun backtrack(trace: List<IntArray>, found: Int, n: Int, m: Int, oldFrom: Int, newFrom: Int, out: MutableList<DiffOp>) {
        val reversed = ArrayList<DiffOp>()
        var x = n
        var y = m
        for (d in found downTo 1) {
            val k = x - y
            val previous = trace[d - 1]
            val left = traceAt(previous, d - 1, k - 1)
            val up = traceAt(previous, d - 1, k + 1)
            val insert = chooseStep(left, up, k, n, m) == STEP_INSERT
            val fromX = if (insert) up else left
            val fromY = fromX - (if (insert) k + 1 else k - 1)
            // The edit lands on (editX, editY); the diagonal run from there to (x, y) is Equal lines.
            val editX = if (insert) fromX else fromX + 1
            val editY = if (insert) fromY + 1 else fromY
            while (x > editX && y > editY) {
                x--
                y--
                reversed += DiffOp.Equal(oldFrom + x, newFrom + y)
            }
            reversed += if (insert) DiffOp.Insert(newFrom + fromY) else DiffOp.Delete(oldFrom + fromX)
            x = fromX
            y = fromY
        }
        while (x > 0 && y > 0) {
            x--
            y--
            reversed += DiffOp.Equal(oldFrom + x, newFrom + y)
        }
        for (i in reversed.indices.reversed()) out += reversed[i]
    }

    private fun traceAt(round: IntArray, d: Int, k: Int): Int = if (k < -d || k > d) UNREACHED else round[(k + d) / 2]

    private fun replaceBlock(oldFrom: Int, oldTo: Int, newFrom: Int, newTo: Int, out: MutableList<DiffOp>) {
        for (i in oldFrom until oldTo) out += DiffOp.Delete(i)
        for (j in newFrom until newTo) out += DiffOp.Insert(j)
    }

    private const val STEP_NONE = 0
    private const val STEP_INSERT = 1
    private const val STEP_DELETE = 2
}
