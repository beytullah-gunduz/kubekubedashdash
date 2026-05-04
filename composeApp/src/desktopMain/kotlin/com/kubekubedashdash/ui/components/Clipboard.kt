package com.kubekubedashdash.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Returns a fire-and-forget callback that copies a string to the system
 * clipboard. Wraps [LocalClipboard]'s suspend API so onClick handlers can
 * call it synchronously without each call site spelling out the
 * coroutine plumbing.
 */
@Composable
fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { text ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(StringSelection(text)))
        }
    }
}
