package com.kubekubedashdash.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary

/**
 * Scrim for a previously-connected session that lost its cluster. The stale
 * content stays rendered (informers keep their last store on a watch-time
 * loss) but is dimmed and made inert: the scrim is the top-most hit target
 * and consumes every pointer event, so no click, drag or wheel scroll reaches
 * the dead cluster's screen. A centred card shows the cause, the retry state,
 * a manual retry and a way out to another cluster. The session's screen, pane
 * and namespace are intentionally untouched — on reconnect the overlay fades
 * and the user is exactly where they were.
 *
 * Keyboard input is not blocked (Compose scrims block pointers only); actions
 * reached that way fail against the dead cluster with their usual surfaces.
 */
@Composable
fun ReconnectOverlay(
    visible: Boolean,
    error: String?,
    retryCountdown: Int,
    isConnecting: Boolean,
    onRetryNow: () -> Unit,
    onSwitchCluster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .semantics { contentDescription = "Connection lost — content disabled until reconnected" }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
            )
            Surface(
                modifier = Modifier.align(Alignment.Center).widthIn(max = 440.dp),
                shape = RoundedCornerShape(12.dp),
                color = KdSurface,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Connection lost",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!error.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = KdTextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Neither connecting nor counting down means the last reconnect
                        // failed without re-arming (or, for at most one frame, the
                        // countdown is between two writes): no spinner, say it plainly.
                        val retryArmed = isConnecting || retryCountdown > 0
                        val statusText = when {
                            isConnecting -> "Reconnecting…"
                            retryCountdown > 0 -> "Retrying in ${retryCountdown}s…"
                            else -> "Automatic retry stopped"
                        }
                        if (retryArmed) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = KdPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = KdTextPrimary,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onRetryNow, enabled = !isConnecting) {
                            Text("Retry now")
                        }
                        OutlinedButton(onClick = onSwitchCluster) {
                            Text("Switch cluster…")
                        }
                    }
                }
            }
        }
    }
}
