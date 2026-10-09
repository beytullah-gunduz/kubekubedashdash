package com.kubekubedashdash.ui.yamledit

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.ui.components.BusyIndicator
import com.kubekubedashdash.yamledit.DiffHunk
import com.kubekubedashdash.yamledit.DiffOp
import com.kubekubedashdash.yamledit.LineDiff
import com.kubekubedashdash.yamledit.SecretRedaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The longest line the diff lays out; a longer one (a Secret's base64 blob) is cut, so one line cannot stall the view. */
internal const val MAX_DIFF_LINE_CHARS = 2_000

internal enum class DiffLineKind { Context, Removed, Added }

/** One row of the diff view: a hunk's header or a line with its numbers (null on the side that lacks it). */
internal sealed interface DiffRow {
    data class HunkHeader(val text: String) : DiffRow

    data class Line(val kind: DiffLineKind, val oldNumber: Int?, val newNumber: Int?, val text: String) : DiffRow
}

/**
 * The rows for [ops] over [oldLines] and [newLines]: for each hunk (changes with
 * [LineDiff.CONTEXT] lines around them) a header `@@ old L / new L @@`, naming the hunk's first line
 * on each side (1-based), then its lines. Lines are numbered as the files are; a line past
 * [MAX_DIFF_LINE_CHARS] is cut with a note of how much is missing. Never throws on an op that points
 * past a list (the lists are the displayed form of the texts the ops were computed on): it shows an empty line.
 */
internal fun diffRows(ops: List<DiffOp>, oldLines: List<String>, newLines: List<String>): List<DiffRow> {
    val rows = ArrayList<DiffRow>()
    for (hunk in LineDiff.hunks(ops)) {
        rows += DiffRow.HunkHeader("@@ old ${hunk.firstOldLine()} / new ${hunk.firstNewLine()} @@")
        for (op in hunk.ops) {
            rows += when (op) {
                is DiffOp.Equal -> DiffRow.Line(DiffLineKind.Context, op.oldIndex + 1, op.newIndex + 1, shown(newLines.getOrNull(op.newIndex)))
                is DiffOp.Delete -> DiffRow.Line(DiffLineKind.Removed, op.oldIndex + 1, null, shown(oldLines.getOrNull(op.oldIndex)))
                is DiffOp.Insert -> DiffRow.Line(DiffLineKind.Added, null, op.newIndex + 1, shown(newLines.getOrNull(op.newIndex)))
            }
        }
    }
    return rows
}

/** The 1-based line a hunk starts at in the old text: its first op that has an old line; 1 for a hunk of insertions alone. */
private fun DiffHunk.firstOldLine(): Int = ops.firstNotNullOfOrNull {
    when (it) {
        is DiffOp.Equal -> it.oldIndex
        is DiffOp.Delete -> it.oldIndex
        is DiffOp.Insert -> null
    }
}?.plus(1) ?: 1

/** The 1-based line a hunk starts at in the new text: its first op that has a new line; 1 for a hunk of deletions alone. */
private fun DiffHunk.firstNewLine(): Int = ops.firstNotNullOfOrNull {
    when (it) {
        is DiffOp.Equal -> it.newIndex
        is DiffOp.Insert -> it.newIndex
        is DiffOp.Delete -> null
    }
}?.plus(1) ?: 1

private fun shown(line: String?): String = when {
    line == null -> ""
    line.length <= MAX_DIFF_LINE_CHARS -> line
    else -> line.substring(0, MAX_DIFF_LINE_CHARS) + "… (${line.length - MAX_DIFF_LINE_CHARS} more characters)"
}

/**
 * A unified-style diff of [oldText] against [newText] ([ops] are their line diff): hunk headers,
 * old and new line numbers, removed lines on a red tint and added lines on a green one, in the
 * mono face. With [mask] on, both texts are shown through [SecretRedaction.displayLines] (a
 * Secret's values masked 1:1 on lines, so [ops] still index them). The rows are built off the UI
 * thread (a 1 MB object has tens of thousands of lines) and laid out lazily; the text can be selected.
 */
@Composable
internal fun DiffView(ops: List<DiffOp>, oldText: String, newText: String, mask: Boolean, modifier: Modifier = Modifier) {
    val rows by produceState<List<DiffRow>?>(null, ops, oldText, newText, mask) {
        value = withContext(Dispatchers.Default) {
            diffRows(ops, SecretRedaction.displayLines(oldText, mask), SecretRedaction.displayLines(newText, mask))
        }
    }
    val built = rows
    Box(modifier = modifier.background(KdSurface)) {
        when {
            built == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BusyIndicator(modifier = Modifier.size(24.dp), color = KdTextSecondary, strokeWidth = 2.dp)
            }

            built.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("The text is unchanged.", style = MaterialTheme.typography.bodyMedium, color = KdTextSecondary)
            }

            else -> {
                val listState = rememberLazyListState()
                val style = MaterialTheme.typography.bodySmall.copy(fontFamily = kdMonoFamily(), fontSize = 11.sp, lineHeight = 16.sp)
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                        items(count = built.size, key = { it }) { index -> DiffRowView(built[index], style) }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun DiffRowView(row: DiffRow, style: TextStyle) {
    when (row) {
        is DiffRow.HunkHeader -> Text(
            row.text,
            style = style,
            color = KdTextSecondary,
            modifier = Modifier.fillMaxWidth().background(KdSurfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp),
        )

        is DiffRow.Line -> {
            val tint = when (row.kind) {
                DiffLineKind.Removed -> KdError.copy(alpha = 0.12f)
                DiffLineKind.Added -> KdSuccess.copy(alpha = 0.12f)
                DiffLineKind.Context -> Color.Transparent
            }
            Row(modifier = Modifier.fillMaxWidth().background(tint).padding(horizontal = 8.dp)) {
                // The numbers and the sign are not part of the text a selection copies.
                DisableSelection {
                    LineNumber(row.oldNumber, style)
                    LineNumber(row.newNumber, style)
                    Text(
                        when (row.kind) {
                            DiffLineKind.Removed -> "-"
                            DiffLineKind.Added -> "+"
                            DiffLineKind.Context -> " "
                        },
                        style = style,
                        color = KdTextSecondary,
                        modifier = Modifier.width(16.dp),
                    )
                }
                Text(row.text, style = style, color = KdTextPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LineNumber(number: Int?, style: TextStyle) {
    Text(
        number?.toString().orEmpty(),
        style = style,
        color = KdTextSecondary.copy(alpha = 0.6f),
        textAlign = TextAlign.End,
        modifier = Modifier.width(40.dp).padding(end = 6.dp),
    )
}
