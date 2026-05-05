// REMOVE in §5.5-D
package com.kubekubedashdash.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.services.WorkspaceManager

// REMOVE in §5.5-D
@Composable
fun DebugLogStreamButton() {
    OutlinedButton(
        onClick = {
            val session = WorkspaceManager.activeSession
            LogStreamRegistry.openOrFocus(session, "test-pod", "default", null)
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, KdBorder),
    ) {
        Text("Open test log stream", color = KdTextPrimary)
    }
}
