package com.kubedash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubedash.KdBorder
import com.kubedash.KdPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.ThemeManager

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(32.dp))

        Text(
            "APPEARANCE",
            style = MaterialTheme.typography.labelMedium,
            color = KdTextSecondary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "Theme",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Choose between dark and light appearance",
            style = MaterialTheme.typography.bodyMedium,
            color = KdTextSecondary,
        )

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ThemePreviewCard(
                label = "Dark",
                selected = ThemeManager.isDarkTheme,
                sidebarColor = Color(0xFF161819),
                backgroundColor = Color(0xFF1E2124),
                surfaceColor = Color(0xFF252A31),
                textColor = Color(0xFFC8D1DC),
                onClick = { ThemeManager.isDarkTheme = true },
            )
            ThemePreviewCard(
                label = "Light",
                selected = !ThemeManager.isDarkTheme,
                sidebarColor = Color(0xFFFFFFFF),
                backgroundColor = Color(0xFFF8FAFC),
                surfaceColor = Color(0xFFE2E8F0),
                textColor = Color(0xFF1E293B),
                onClick = { ThemeManager.isDarkTheme = false },
            )
        }
    }
}

@Composable
private fun ThemePreviewCard(
    label: String,
    selected: Boolean,
    sidebarColor: Color,
    backgroundColor: Color,
    surfaceColor: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) KdPrimary else KdBorder
    val borderWidth = if (selected) 2.dp else 1.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 160.dp, height = 110.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Mini sidebar
                Column(
                    modifier = Modifier
                        .width(40.dp)
                        .fillMaxSize()
                        .background(sidebarColor)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(4) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(textColor.copy(alpha = if (it == 0) 0.3f else 0.12f)),
                        )
                    }
                }

                // Mini content area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(textColor.copy(alpha = 0.25f)),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(surfaceColor),
                            )
                        }
                    }
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(textColor.copy(alpha = 0.10f)),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(KdPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, KdBorder, CircleShape),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onBackground else KdTextSecondary,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}
