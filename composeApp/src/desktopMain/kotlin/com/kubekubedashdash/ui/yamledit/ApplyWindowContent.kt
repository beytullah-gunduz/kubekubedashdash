package com.kubekubedashdash.ui.yamledit

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.ui.components.BusyIndicator
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.yamledit.session.ApplyPhase
import com.kubekubedashdash.yamledit.session.ApplyYamlSession
import com.kubekubedashdash.yamledit.session.DocRow
import com.kubekubedashdash.yamledit.session.DocStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The status strip's text while the buffer is empty: what the window is for. */
private const val EMPTY_HINT = "Paste or open one or more manifests; separate documents with ---"

/**
 * The content of the Apply YAML window (D12): toolbar (search, "Open file…", "Review", "Close"), the
 * editor while editing, the review rows once "Review" ran (one per document, with the dry run's
 * answer) and the footer that applies them after a confirmation. [buffer] is the session's own
 * buffer, the Swing editor.
 *
 * Like the edit window, at most one dialog shows at a time — the unsaved-changes prompt before the
 * "Apply N documents?" confirmation (cancelling the prompt brings the confirmation back) — and while
 * one shows the Swing editor is not composed: Compose draws dialogs under a Swing component. The
 * review is pure Compose, so it needs no such care. Esc does nothing here; the window closes only
 * through its own close paths.
 */
@Composable
internal fun ApplyWindowContent(session: ApplyYamlSession, buffer: RstaBuffer) {
    val phase by session.phase.collectAsState()
    val canApply by session.canApply.collectAsState()
    val closePrompt by session.closePrompt.collectAsState()
    val search = rememberYamlSearch(buffer)
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Why "Open file…" did not load a file; shown under the toolbar until dismissed or the next review.
    var openError by remember { mutableStateOf<String?>(null) }
    // The "Apply N documents?" dialog is window state: the session has no confirming phase.
    var confirming by remember { mutableStateOf(false) }

    val review: () -> Unit = {
        openError = null
        session.review()
    }

    // The Swing key bindings (Cmd/Ctrl+S, +F, +W) call back into this window; they are only live while it exists.
    DisposableEffect(buffer, session, searchFocus) {
        buffer.onReview = review
        buffer.onFind = { runCatching { searchFocus.requestFocus() } }
        buffer.onCloseRequest = session::requestClose
        onDispose {
            buffer.onReview = {}
            buffer.onFind = {}
            buffer.onCloseRequest = {}
        }
    }

    val currentPhase = phase
    val editing = currentPhase is ApplyPhase.Editing
    val reviewing = currentPhase is ApplyPhase.Reviewing
    // A confirmation left over from a review that is gone must not pop up in the next one.
    LaunchedEffect(reviewing) { if (!reviewing) confirming = false }
    val confirmShown = confirming && reviewing && canApply
    val dialogShown = closePrompt || confirmShown

    val openFile: () -> Unit = {
        // The native dialog runs here, on the EDT, as the directory picker does; the read hops off it.
        val file = YamlFilePicker.pick()
        if (file != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { readManifestFile(file) }
                // Back on the EDT (Swing): a review started meanwhile has taken the text already.
                if (session.phase.value is ApplyPhase.Editing) {
                    when (loaded) {
                        is ManifestFile.Loaded -> {
                            openError = null
                            buffer.replaceAll(loaded.text, resetHistory = false)
                        }

                        is ManifestFile.Rejected -> openError = loaded.message
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(KdSurface)) {
        EditorToolbarFrame(
            title = "Apply YAML",
            subtitle = "${session.context} · namespace for documents without one: ${session.defaultNamespace}",
            titleMaxWidth = null,
        ) {
            if (editing) {
                SearchControls(search, searchFocus, Modifier.width(240.dp))
                TextButton(
                    onClick = openFile,
                    colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                    shape = kdRoundShape,
                ) {
                    Text("Open file…", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = review,
                    colors = ButtonDefaults.textButtonColors(contentColor = KdPrimary),
                    shape = kdRoundShape,
                ) {
                    Text("Review", style = MaterialTheme.typography.labelMedium)
                }
            }
            TextButton(
                onClick = session::requestClose,
                enabled = currentPhase !is ApplyPhase.Applying,
                colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                shape = kdRoundShape,
            ) {
                Text("Close", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (editing) openError?.let { message -> OpenFileErrorStrip(message, onDismiss = { openError = null }) }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (currentPhase) {
                ApplyPhase.Editing -> if (dialogShown) {
                    EditorHiddenPlaceholder()
                } else {
                    YamlEditorHost(buffer, Modifier.fillMaxSize())
                }

                is ApplyPhase.Reviewing -> RowsPane(
                    rows = currentPhase.rows,
                    summary = applyReviewSummary(currentPhase.rows, currentPhase.running),
                    busy = currentPhase.running,
                    applyStage = false,
                    currentIndex = null,
                    notRolledBack = false,
                    footer = {
                        ReviewFooter(
                            canApply = canApply,
                            changeCount = applyChangeCount(currentPhase.rows),
                            onBack = session::backToEditor,
                            onApply = { confirming = true },
                        )
                    },
                )

                is ApplyPhase.Applying -> RowsPane(
                    rows = currentPhase.rows,
                    summary = "Applying ${applyDocumentCount(currentPhase.rows.size)}…",
                    busy = true,
                    applyStage = true,
                    // The document being applied now: the first one the apply has not reached.
                    currentIndex = currentPhase.rows.firstOrNull { it.status is DocStatus.Planned }?.index,
                    notRolledBack = false,
                    footer = { ApplyingFooter() },
                )

                is ApplyPhase.Done -> RowsPane(
                    rows = currentPhase.rows,
                    summary = applyDoneSummary(currentPhase.rows),
                    busy = false,
                    applyStage = true,
                    currentIndex = null,
                    notRolledBack = currentPhase.rows.any { it.status is DocStatus.Failed },
                    footer = { DoneFooter(onBack = session::backToEditor) },
                )
            }
        }
        if (editing) LiveStatusStrip(session, buffer, search)
    }

    if (closePrompt) {
        ConfirmActionDialog(
            title = "Discard your changes?",
            body = "Your edits to ${session.dirtyLabel} haven't been applied.",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = session::confirmClose,
            onDismiss = session::cancelClose,
        )
    } else if (confirmShown) {
        ConfirmActionDialog(
            title = applyConfirmTitle(applyChangeCount(currentPhase.rows)),
            body = applyConfirmBody(session.context),
            confirmLabel = "Apply",
            destructive = false,
            onConfirm = {
                confirming = false
                session.confirmApply()
            },
            onDismiss = { confirming = false },
        )
    }
}

/** The status strip under the editor: what the checks found, whether the text is modified, and its line count. */
@Composable
private fun LiveStatusStrip(session: ApplyYamlSession, buffer: RstaBuffer, search: YamlSearchState) {
    val parseProblem by session.parseProblem.collectAsState()
    val problems by session.problems.collectAsState()
    val dirty by session.dirty.collectAsState()
    // Re-read after every edit; asking the Swing document is cheap.
    val lineCount = remember(search.bufferChanges) { buffer.lineCount }
    val blank = remember(search.bufferChanges) { lineCount <= 1 && buffer.text().isBlank() }
    EditStatusStrip(
        parseProblem = parseProblem,
        problems = problems,
        dirty = dirty,
        lineCount = lineCount,
        secretUnmasked = false,
        cleanLabel = if (blank) EMPTY_HINT else "No changes since the last apply",
        onJump = buffer::moveCaretTo,
    )
}

/** Why a file could not be opened, with a way to dismiss it. */
@Composable
private fun OpenFileErrorStrip(message: String, onDismiss: () -> Unit) {
    CompactControls {
        Surface(color = KdError.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(message, style = MaterialTheme.typography.labelMedium, color = KdTextPrimary, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = KdPrimary),
                    shape = kdRoundShape,
                ) {
                    Text("Dismiss", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * The review / apply / done body: [summary] with a busy ring while [busy], the partial-failure
 * banner when [notRolledBack] (an apply finished with a failed document), the rows and the
 * [footer]. [applyStage] = the rows are an apply's rather than a review's, which changes how they
 * read; [currentIndex] is the row being applied now, if any.
 */
@Composable
internal fun RowsPane(
    rows: List<DocRow>,
    summary: String,
    busy: Boolean,
    applyStage: Boolean,
    currentIndex: Int?,
    notRolledBack: Boolean,
    footer: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CompactControls {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) BusyIndicator(modifier = Modifier.size(14.dp), color = KdTextSecondary, strokeWidth = 1.5.dp)
                Text(
                    summary,
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (notRolledBack) {
            Surface(color = KdWarning.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    APPLY_NOT_ROLLED_BACK,
                    style = MaterialTheme.typography.labelMedium,
                    color = KdTextPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(KdSurface)) {
            val listState = rememberLazyListState()
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                    items(rows, key = { it.index }) { row ->
                        ApplyRowView(row, applyStage = applyStage, current = row.index == currentIndex)
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        footer()
    }
}

/** `#n  line L  Kind ns/name  (default namespace)  status`; a long failure message wraps in the status column. */
@Composable
internal fun ApplyRowView(row: DocRow, applyStage: Boolean, current: Boolean) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = kdMonoFamily(), fontSize = 12.sp, lineHeight = 17.sp)
    val body = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp)
    val statusColor = when (applyRowTone(row.status)) {
        ApplyTone.Muted -> KdTextSecondary
        ApplyTone.Normal -> KdTextPrimary
        ApplyTone.Success -> KdSuccess
        ApplyTone.Error -> KdError
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("#${row.index + 1}", style = mono, color = KdTextSecondary, modifier = Modifier.width(36.dp))
            Text("line ${row.line}", style = mono, color = KdTextSecondary, modifier = Modifier.width(64.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(row.label, style = mono, color = KdTextPrimary, modifier = Modifier.weight(1f, fill = false))
                if (row.namespaceDefaulted) Text(APPLY_DEFAULTED_NAMESPACE_TAG, style = body, color = KdTextSecondary)
            }
            Column(modifier = Modifier.weight(1.3f)) {
                Text(applyRowStatusText(row.status, applyStage, current), style = body, color = statusColor)
                applyRowDemoFlag(row.status, applyStage)?.let { flag -> Text(flag, style = body, color = KdWarning) }
            }
        }
        HorizontalDivider(color = KdBorder.copy(alpha = 0.5f))
    }
}

@Composable
private fun FooterRow(content: @Composable RowScope.() -> Unit) {
    CompactControls {
        Row(
            modifier = Modifier.fillMaxWidth().background(KdSurfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun BackToEditorButton(onClick: () -> Unit, enabled: Boolean) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
        shape = kdRoundShape,
    ) {
        Text("Back to editor", style = MaterialTheme.typography.labelMedium)
    }
}

/** After the dry runs: "Back to editor" and "Apply N documents…", enabled only when every document passed and one changes something. */
@Composable
internal fun ReviewFooter(canApply: Boolean, changeCount: Int, onBack: () -> Unit, onApply: () -> Unit) {
    FooterRow {
        BackToEditorButton(onClick = onBack, enabled = true)
        Box(Modifier.weight(1f))
        Button(onClick = onApply, enabled = canApply, shape = kdRoundShape) {
            Text(applyButtonLabel(changeCount), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** While documents are being applied nothing can be changed or left. */
@Composable
internal fun ApplyingFooter() {
    FooterRow {
        BackToEditorButton(onClick = {}, enabled = false)
        Box(Modifier.weight(1f))
        Button(onClick = {}, enabled = false, shape = kdRoundShape) {
            Text("Applying…", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** After an apply: the way back to the text (the summary above says how it went; the window's own "Close" leaves). */
@Composable
internal fun DoneFooter(onBack: () -> Unit) {
    FooterRow {
        BackToEditorButton(onClick = onBack, enabled = true)
    }
}
