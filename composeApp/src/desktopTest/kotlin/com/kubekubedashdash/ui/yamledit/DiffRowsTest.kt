package com.kubekubedashdash.ui.yamledit

import com.kubekubedashdash.yamledit.DiffOp
import com.kubekubedashdash.yamledit.LineDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The review's diff rows, built without any UI: hunk headers, line numbers, truncation. */
class DiffRowsTest {

    private val old = listOf("a", "b", "c", "d", "e", "f", "g", "h")

    private fun rows(old: List<String>, new: List<String>): List<DiffRow> = diffRows(LineDiff.diff(old, new), old, new)

    @Test
    fun `a change in the middle is one hunk with three lines of context and a header naming both start lines`() {
        val new = old.toMutableList().also { it[4] = "E" }

        val rows = rows(old, new)

        assertEquals(DiffRow.HunkHeader("@@ old 2 / new 2 @@"), rows.first())
        val lines = rows.drop(1).map { assertIs<DiffRow.Line>(it) }
        assertEquals(listOf("b", "c", "d", "e", "E", "f", "g", "h"), lines.map { it.text })
        assertEquals(
            listOf(
                DiffLineKind.Context,
                DiffLineKind.Context,
                DiffLineKind.Context,
                DiffLineKind.Removed,
                DiffLineKind.Added,
                DiffLineKind.Context,
                DiffLineKind.Context,
                DiffLineKind.Context,
            ),
            lines.map { it.kind },
        )
    }

    @Test
    fun `lines carry the numbers of the side they exist on`() {
        val new = old.toMutableList().also { it[4] = "E" }

        val lines = rows(old, new).filterIsInstance<DiffRow.Line>()

        val removed = lines.single { it.kind == DiffLineKind.Removed }
        assertEquals(5, removed.oldNumber)
        assertEquals(null, removed.newNumber)
        val added = lines.single { it.kind == DiffLineKind.Added }
        assertEquals(null, added.oldNumber)
        assertEquals(5, added.newNumber)
        val context = lines.first()
        assertEquals(2, context.oldNumber)
        assertEquals(2, context.newNumber)
    }

    @Test
    fun `two distant changes are two hunks`() {
        val long = (1..30).map { "line$it" }
        val new = long.toMutableList().also {
            it[1] = "changed2"
            it[27] = "changed28"
        }

        val headers = rows(long, new).filterIsInstance<DiffRow.HunkHeader>()

        assertEquals(listOf("@@ old 1 / new 1 @@", "@@ old 25 / new 25 @@"), headers.map { it.text })
    }

    @Test
    fun `an identical text has no rows`() {
        assertTrue(rows(old, old).isEmpty())
    }

    @Test
    fun `a hunk of insertions alone starts at line 1 on the old side`() {
        val rows = diffRows(listOf(DiffOp.Insert(0), DiffOp.Insert(1)), emptyList(), listOf("x", "y"))

        assertEquals(DiffRow.HunkHeader("@@ old 1 / new 1 @@"), rows.first())
        assertEquals(listOf("x", "y"), rows.filterIsInstance<DiffRow.Line>().map { it.text })
    }

    @Test
    fun `a very long line is cut with a note of how much is missing`() {
        val huge = "x".repeat(MAX_DIFF_LINE_CHARS + 3_000)

        val line = rows(listOf("a"), listOf("a", huge)).filterIsInstance<DiffRow.Line>().single { it.kind == DiffLineKind.Added }

        assertEquals("x".repeat(MAX_DIFF_LINE_CHARS) + "… (3000 more characters)", line.text)
    }

    @Test
    fun `an op that points past the lines shows an empty line instead of failing`() {
        val rows = diffRows(listOf(DiffOp.Delete(5), DiffOp.Insert(5)), listOf("a"), listOf("b"))

        assertEquals(listOf("", ""), rows.filterIsInstance<DiffRow.Line>().map { it.text })
    }
}
