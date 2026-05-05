package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.ui.components.kdFocusRing
import org.jetbrains.compose.resources.painterResource

enum class LogDrawerState { HIDDEN, COLLAPSED, EXPANDED }

@Composable
fun LogDrawer(
    state: LogDrawerState,
    onStateChange: (LogDrawerState) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = { Text("No active log streams") },
) {
    AnimatedVisibility(
        visible = state != LogDrawerState.HIDDEN,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(KdSurface)) {
            HorizontalDivider(color = KdBorder, thickness = 1.dp)
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 24.dp)
                        .background(KdBorder, shape = RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        onStateChange(
                            if (state == LogDrawerState.EXPANDED) {
                                LogDrawerState.COLLAPSED
                            } else {
                                LogDrawerState.EXPANDED
                            },
                        )
                    },
                    modifier = Modifier.kdFocusRing(),
                ) {
                    Icon(
                        painter = painterResource(
                            if (state == LogDrawerState.EXPANDED) {
                                Res.drawable.keyboard_arrow_down_filled
                            } else {
                                Res.drawable.keyboard_arrow_up_filled
                            },
                        ),
                        contentDescription = if (state == LogDrawerState.EXPANDED) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onStateChange(LogDrawerState.HIDDEN) },
                    modifier = Modifier.kdFocusRing(),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.close_filled),
                        contentDescription = "Close log drawer",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state == LogDrawerState.EXPANDED) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
            }
        }
    }
}
