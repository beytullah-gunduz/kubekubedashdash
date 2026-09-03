package com.kubekubedashdash.ui.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdInfo
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_circle_filled
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.error_filled
import com.kubekubedashdash.resources.info_filled
import com.kubekubedashdash.resources.warning_filled
import org.jetbrains.compose.resources.painterResource

/**
 * Installs the per-window action feedback layer: one [ActionFeedbackState]
 * (EDT-scoped, remembered for the window's life) provided through
 * [LocalActionFeedback], and the toast stack drawn bottom-right above
 * [content]. Installed once per window by `KubeDashTheme`, next to the
 * "Copied" pill host. The state is a stable remembered object, so the static
 * local never changes identity and never forces a full-window recomposition.
 */
@Composable
fun ActionFeedbackHost(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { ActionFeedbackState(scope) }
    CompositionLocalProvider(LocalActionFeedback provides state) {
        Box(Modifier.fillMaxSize()) {
            content()
            ToastHost(state, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

/**
 * The stack itself. An empty stack is a plain Column with no pointer
 * modifiers, so it is not a hit target and everything beneath stays live.
 * Each card carries a pointer-input node, which is what keeps a click on the
 * card body from falling through to the row under it: hit testing stops at
 * the top-most subtree that has one. Each card is its own polite live region
 * so a screen reader announces "Success, Cordoned Node …" as one string.
 */
@Composable
fun ToastHost(state: ActionFeedbackState, modifier: Modifier = Modifier) {
    val toasts by state.toasts.collectAsState()
    Column(
        modifier = modifier
            .padding(16.dp)
            .width(360.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        toasts.forEach { toast ->
            key(toast.id) {
                // Enter animation only: removal is instant, so a dismissed or
                // expired toast never lingers as a stale hit target.
                val appeared = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = appeared,
                    enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 3 },
                ) {
                    ToastCard(
                        toast = toast,
                        onUndo = { state.undo(toast.id) },
                        onDismiss = { state.dismiss(toast.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToastCard(toast: Toast, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val (icon, tint) = when (toast.kind) {
        ToastKind.Success -> Res.drawable.check_circle_filled to KdSuccess
        ToastKind.Failure -> Res.drawable.error_filled to KdError
        ToastKind.Warning -> Res.drawable.warning_filled to KdWarning
        ToastKind.Info -> Res.drawable.info_filled to KdInfo
    }
    val kindLabel = when (toast.kind) {
        ToastKind.Success -> "Success"
        ToastKind.Failure -> "Error"
        ToastKind.Warning -> "Warning"
        ToastKind.Info -> "Info"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .pointerInput(Unit) {
                // Presence alone blocks fall-through (hit testing stops at this
                // subtree). Consume press, release and wheel so no ancestor
                // reacts to them — but NEVER a move: the Undo/× buttons check
                // the Final pass of every move for consumption while pressed
                // (waitForUpOrCancellation), and a consumed move cancels their
                // tap. A parent's Main pass runs before that Final check.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val terminal = event.type == PointerEventType.Press ||
                            event.type == PointerEventType.Release ||
                            event.type == PointerEventType.Scroll
                        if (terminal) event.changes.forEach { if (!it.isConsumed) it.consume() }
                    }
                }
            },
        shape = RoundedCornerShape(8.dp),
        color = KdSurface,
        border = BorderStroke(1.dp, KdBorder),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = kindLabel,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    toast.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KdTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = toast.detail
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = KdTextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (toast.undo != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onUndo, enabled = !toast.undoInFlight) {
                    Text(if (toast.undoInFlight) "Undoing…" else "Undo")
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(Res.drawable.close_filled),
                    contentDescription = "Dismiss",
                    tint = KdTextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
