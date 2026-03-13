package com.kubekubedashdash.ui.modals

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.logging.AppLogStore
import com.kubekubedashdash.util.CheckStatus
import com.kubekubedashdash.util.PrerequisiteCheck
import com.kubekubedashdash.util.PrerequisiteResult

@Composable
fun PrerequisitesModal(
    result: PrerequisiteResult,
    onQuit: () -> Unit,
    onIgnore: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 780.dp),
            shape = RoundedCornerShape(12.dp),
            color = KdSurface,
            border = BorderStroke(1.dp, KdBorder),
            shadowElevation = 16.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(KdPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Rocket,
                            contentDescription = null,
                            tint = KdPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "System Check",
                            style = MaterialTheme.typography.titleMedium,
                            color = KdTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Verifying prerequisites…",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                        )
                    }
                }

                HorizontalDivider(color = KdBorder, thickness = 1.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    result.checks.forEach { check ->
                        CheckRow(check)
                    }
                }

                HorizontalDivider(color = KdBorder, thickness = 1.dp)
                LogOutputPanel()

                if (!result.isRunning && !result.allPassed) {
                    HorizontalDivider(color = KdBorder, thickness = 1.dp)

                    run {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = KdError,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Required prerequisites are missing. The application cannot operate.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KdError,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = onIgnore,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, KdBorder),
                                ) {
                                    Text("Continue", color = KdTextSecondary)
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = onQuit,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = KdError),
                                ) {
                                    Text("Quit", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogOutputPanel() {
    val entries by AppLogStore.entries.collectAsState()
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            "Log Output",
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 150.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.85f)),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
                    .horizontalScroll(horizontalScrollState),
            ) {
                LazyColumn(state = listState) {
                    items(entries) { entry ->
                        val color = when (entry.level) {
                            "ERROR" -> KdError
                            "WARN" -> KdWarning
                            "DEBUG" -> KdTextSecondary
                            else -> Color(0xFFCCCCCC)
                        }
                        Text(
                            entry.formattedMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            VerticalScrollbar(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = 2.dp, bottom = 14.dp, end = 2.dp)
                    .heightIn(min = 80.dp, max = 150.dp),
                adapter = rememberScrollbarAdapter(listState),
            )
            HorizontalScrollbar(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 2.dp, end = 14.dp, bottom = 2.dp)
                    .fillMaxWidth(),
                adapter = rememberScrollbarAdapter(horizontalScrollState),
            )
        }
    }
}

@Composable
private fun CheckRow(check: PrerequisiteCheck) {
    val statusColor by animateColorAsState(
        targetValue = when (check.status) {
            CheckStatus.CHECKING -> KdTextSecondary
            CheckStatus.PASSED -> KdSuccess
            CheckStatus.WARN -> KdWarning
            CheckStatus.FAILED -> KdError
        },
        animationSpec = tween(300),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(statusColor.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (check.status) {
                CheckStatus.CHECKING -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = KdPrimary,
                    strokeWidth = 2.dp,
                )

                CheckStatus.PASSED -> Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(KdSuccess.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = KdSuccess)
                }

                CheckStatus.WARN -> Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(KdWarning.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Warning, null, Modifier.size(13.dp), tint = KdWarning)
                }

                CheckStatus.FAILED -> Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(KdError.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, null, Modifier.size(13.dp), tint = KdError)
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                check.name,
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                check.detail ?: check.description,
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
