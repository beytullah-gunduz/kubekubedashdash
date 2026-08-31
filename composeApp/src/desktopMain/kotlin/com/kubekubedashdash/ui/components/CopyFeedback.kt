package com.kubekubedashdash.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import kotlinx.coroutines.delay

/**
 * Shows a transient confirmation pill. Defaults to a no-op so
 * `rememberCopyToClipboard()` is safe in any composition that does not
 * install [CopyFeedbackHost].
 */
val LocalCopyFeedback = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Hosts the "Copied" pill. Installed once per window by `KubeDashTheme`.
 *
 * SECURITY: the pill renders the LABEL only, never the copied text. Copy sites
 * include Secret YAML and the MCP bearer token, and this app's screenshots are
 * published to a public GitHub Pages site.
 */
@Composable
fun CopyFeedbackHost(content: @Composable () -> Unit) {
    // `message` is retained while the pill fades out — clearing it would blank
    // the text mid-animation. `seq`, not the label, keys the hide timer so two
    // identical copies in a row restart it instead of reusing the first effect.
    var message by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var seq by remember { mutableIntStateOf(0) }
    // Remembered deliberately. LocalCopyFeedback is a staticCompositionLocalOf,
    // which skips subtree diffing: a value that changes identity recomposes the
    // ENTIRE provider content, and this host wraps the whole window. `seq += 1`
    // invalidates this scope on every copy, so an unremembered lambda would hand
    // the local a fresh instance per copy and force a full-window recomposition.
    // It only writes through captured MutableState delegates, so a keyless
    // remember is safe.
    val show: (String) -> Unit = remember {
        { label: String ->
            message = label
            visible = true
            seq += 1
        }
    }
    LaunchedEffect(seq) {
        if (seq == 0) return@LaunchedEffect
        delay(1500)
        visible = false
    }
    CompositionLocalProvider(LocalCopyFeedback provides show) {
        Box(Modifier.fillMaxSize()) {
            content()
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(220)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            ) {
                CopiedPill(message)
            }
        }
    }
}

@Composable
private fun CopiedPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(KdSurface)
            .border(1.dp, KdBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = KdTextPrimary)
    }
}
