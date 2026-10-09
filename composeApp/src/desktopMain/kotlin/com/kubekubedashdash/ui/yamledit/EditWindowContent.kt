package com.kubekubedashdash.ui.yamledit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.ui.components.BusyIndicator
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.yamledit.LineDiff
import com.kubekubedashdash.yamledit.session.ApplyState
import com.kubekubedashdash.yamledit.session.DryRunState
import com.kubekubedashdash.yamledit.session.EditBanner
import com.kubekubedashdash.yamledit.session.EditPhase
import com.kubekubedashdash.yamledit.session.YamlEditSession

/**
 * The content of one YAML edit window (D1): toolbar, the banner when the server moved on, the body
 * for the session's phase and, while editing, the status strip. [buffer] is the session's own
 * buffer, the Swing editor.
 *
 * At most one dialog shows at a time, the close prompt before the apply confirmation (cancelling
 * the prompt brings the confirmation back), and while any dialog shows the Swing editor is not
 * composed (a placeholder stands in): Compose draws dialogs under a Swing component (D1). The
 * review is pure Compose, so it needs no such care. Esc does nothing here; the window closes only
 * through its own close paths, each behind the unsaved-changes prompt.
 */
@Composable
internal fun EditWindowContent(session: YamlEditSession, buffer: RstaBuffer) {
    val phase by session.phase.collectAsState()
    val banner by session.banner.collectAsState()
    val apply by session.apply.collectAsState()
    val closePrompt by session.closePrompt.collectAsState()
    val masking by PreferenceRepository.maskSecretValues.collectAsState()
    val search = rememberYamlSearch(buffer)
    val searchFocus = remember { FocusRequester() }

    // The Swing key bindings (Cmd/Ctrl+S, +F, +W) call back into this window; they are only live while it exists.
    DisposableEffect(buffer, session, searchFocus) {
        buffer.onReview = session::review
        buffer.onFind = { runCatching { searchFocus.requestFocus() } }
        buffer.onCloseRequest = session::requestClose
        onDispose {
            buffer.onReview = {}
            buffer.onFind = {}
            buffer.onCloseRequest = {}
        }
    }

    val applyState = apply
    val applyDialogShown = applyState is ApplyState.Confirming || applyState is ApplyState.InFlight || applyState is ApplyState.Failed
    val dialogShown = closePrompt || applyDialogShown
    val target = session.target
    val currentPhase = phase
    val editing = currentPhase is EditPhase.Editing

    Column(modifier = Modifier.fillMaxSize().background(KdSurface)) {
        EditToolbar(
            kind = target.kind,
            subtitle = "${target.ref} · ${session.context}",
            search = if (editing) search else null,
            searchFocus = searchFocus,
            reviewEnabled = editing && banner !is EditBanner.Deleted,
            onReview = session::review,
            onClose = session::requestClose,
        )
        banner?.let { shown ->
            EditBannerStrip(
                banner = shown,
                kind = target.kind,
                actionsEnabled = applyState !is ApplyState.InFlight,
                onCompare = session::compareWithLatest,
                onDiscard = session::discardEdits,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (currentPhase) {
                EditPhase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BusyIndicator(modifier = Modifier.size(32.dp), color = KdPrimary, strokeWidth = 3.dp)
                }

                is EditPhase.LoadFailed -> LoadFailedBody(currentPhase.message, onRetry = session::retryLoad, onClose = session::requestClose)

                EditPhase.Editing -> if (dialogShown) {
                    EditorHiddenPlaceholder()
                } else {
                    YamlEditorHost(buffer, Modifier.fillMaxSize())
                }

                is EditPhase.Reviewing -> ReviewPane(session, currentPhase, banner, applyState, masking)
            }
        }
        if (editing) LiveStatusStrip(session, buffer, search, masking)
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
    } else if (applyDialogShown) {
        ConfirmActionDialog(
            title = "Apply changes?",
            body = "Replace ${target.kind} \"${target.ref}\" on ${session.context} with your edited version. Kubernetes keeps no undo for this.",
            confirmLabel = "Apply",
            destructive = false,
            inFlight = applyState is ApplyState.InFlight,
            errorMessage = (applyState as? ApplyState.Failed)?.message,
            onConfirm = {
                // After a failure the dialog stays open with the reason; Apply asks again, with the same reviewed body.
                if (applyState is ApplyState.Failed) session.requestApply()
                session.confirmApply()
            },
            onDismiss = session::cancelApply,
        )
    }
}

/** The status strip, reading the live parts: what the checks found, whether the text is modified and its line count. */
@Composable
private fun LiveStatusStrip(session: YamlEditSession, buffer: RstaBuffer, search: YamlSearchState, masking: Boolean) {
    val parseProblem by session.parseProblem.collectAsState()
    val problems by session.problems.collectAsState()
    val dirty by session.dirty.collectAsState()
    // Re-read after every edit; reading the count of the Swing document is cheap.
    val lineCount = remember(search.bufferChanges) { buffer.lineCount }
    EditStatusStrip(
        parseProblem = parseProblem,
        problems = problems,
        dirty = dirty,
        lineCount = lineCount,
        secretUnmasked = session.isSecret && masking,
        onJump = buffer::moveCaretTo,
    )
}

/**
 * The review: the diff of the edit against the live object, the dry run's outcome and the footer.
 * "Apply…" is enabled only for a passed dry run with no banner up; a review with no effective
 * change (even when the text differs: formatting, comments) says so and cannot be applied. A
 * Secret with masking on shows its diff masked until "Reveal" (local to this review).
 */
@Composable
private fun ReviewPane(
    session: YamlEditSession,
    review: EditPhase.Reviewing,
    banner: EditBanner?,
    apply: ApplyState,
    masking: Boolean,
) {
    var reveal by remember { mutableStateOf(false) }
    val maskable = session.isSecret && masking
    val noEffect = review.dryRun is DryRunState.NoChanges
    val changed = LineDiff.changedLineCount(review.ops)
    Column(modifier = Modifier.fillMaxSize()) {
        CompactControls {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (noEffect) {
                        "No effective changes"
                    } else if (changed == 1) {
                        "1 line changed"
                    } else {
                        "$changed lines changed"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                DryRunChip(review.dryRun)
                Box(Modifier.weight(1f))
                if (maskable) {
                    TextButton(
                        onClick = { reveal = !reveal },
                        colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                        shape = kdRoundShape,
                    ) {
                        Text(if (reveal) "Hide" else "Reveal", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        (review.dryRun as? DryRunState.Failed)?.let { failed -> DryRunMessage(failed.message) }
        DiffView(
            ops = review.ops,
            oldText = review.oldText,
            newText = review.newText,
            mask = maskable && !reveal,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        CompactControls {
            Row(
                modifier = Modifier.fillMaxWidth().background(KdSurfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = session::backToEditor,
                    enabled = apply !is ApplyState.InFlight,
                    colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                    shape = kdRoundShape,
                ) {
                    Text("Back to editor", style = MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.weight(1f))
                Button(
                    onClick = session::requestApply,
                    enabled = review.dryRun is DryRunState.Passed && banner == null,
                    shape = kdRoundShape,
                ) {
                    Text("Apply…", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** A failed dry run's message, verbatim (the session already scrubbed a Secret's values), selectable and scrollable. */
@Composable
private fun DryRunMessage(message: String) {
    Surface(
        shape = 6.dp.kdCorner,
        color = KdError.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        SelectionContainer {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = kdMonoFamily(), fontSize = 11.sp, lineHeight = 16.sp),
                color = KdTextPrimary,
                modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(8.dp),
            )
        }
    }
}
