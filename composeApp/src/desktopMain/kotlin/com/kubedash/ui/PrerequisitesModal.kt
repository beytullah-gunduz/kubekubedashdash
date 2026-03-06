package com.kubedash.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubedash.CheckStatus
import com.kubedash.KdBorder
import com.kubedash.KdError
import com.kubedash.KdPrimary
import com.kubedash.KdSuccess
import com.kubedash.KdSurface
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import com.kubedash.PrerequisiteCheck
import com.kubedash.PrerequisiteResult

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
            modifier = Modifier.widthIn(max = 520.dp),
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
