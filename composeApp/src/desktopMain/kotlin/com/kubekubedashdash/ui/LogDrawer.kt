package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.ui.components.DrawerLogPane
import com.kubekubedashdash.ui.components.kdFocusRing
import org.jetbrains.compose.resources.painterResource

enum class LogDrawerState { HIDDEN, COLLAPSED, EXPANDED }

val DrawerHeaderHeight = 48.dp
val DrawerBodyHeight = 308.dp

@Composable
fun LogDrawer(
    state: LogDrawerState,
    onStateChange: (LogDrawerState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val streams by LogStreamRegistry.streams.collectAsState()
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }

    AnimatedVisibility(
        visible = state != LogDrawerState.HIDDEN,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(KdSurface)) {
            HorizontalDivider(color = KdBorder, thickness = 1.dp)

            Row(
                modifier = Modifier.fillMaxWidth().height(DrawerHeaderHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (streams.isNotEmpty()) {
                        val streamList = streams.values.sortedBy { it.openedAt }
                        val displayedKey = focusedKey?.takeIf { it in streams }
                            ?: streamList.first().id.key
                        val selectedIndex = streamList
                            .indexOfFirst { it.id.key == displayedKey }
                            .coerceAtLeast(0)
                        PrimaryScrollableTabRow(
                            selectedTabIndex = selectedIndex,
                            edgePadding = 0.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            streamList.forEach { stream ->
                                Tab(
                                    selected = stream.id.key == displayedKey,
                                    onClick = {
                                        focusedKey = stream.id.key
                                        if (state == LogDrawerState.COLLAPSED) {
                                            onStateChange(LogDrawerState.EXPANDED)
                                        }
                                    },
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                stream.displayLabel,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .pointerInput(stream.id) {
                                                        detectTapGestures(onTap = { LogStreamRegistry.close(stream.id) })
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    painter = painterResource(Res.drawable.close_filled),
                                                    contentDescription = "Close ${stream.displayLabel}",
                                                    modifier = Modifier.size(10.dp),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

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

            AnimatedVisibility(
                visible = state == LogDrawerState.EXPANDED,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(DrawerBodyHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    if (streams.isEmpty()) {
                        Text(
                            "Log streams stay open across navigation.\nOpen one from a pod's row menu — Cmd/Ctrl+J reopens this drawer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = KdTextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    } else {
                        val streamList = streams.values.sortedBy { it.openedAt }
                        val displayedKey = focusedKey?.takeIf { it in streams }
                            ?: streamList.first().id.key
                        DrawerLogPane(
                            stream = streams.getValue(displayedKey),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
