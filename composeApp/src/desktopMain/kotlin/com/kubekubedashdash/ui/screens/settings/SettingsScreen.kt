package com.kubekubedashdash.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ui.screens.settings.viewmodel.SettingsScreenViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsScreenViewModel = viewModel { SettingsScreenViewModel() },
) {
    var showAboutModal by remember { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isReady = true }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isReady) return@Box
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
                    selected = viewModel.isDarkTheme,
                    sidebarColor = Color(0xFF161819),
                    backgroundColor = Color(0xFF1E2124),
                    surfaceColor = Color(0xFF252A31),
                    textColor = Color(0xFFC8D1DC),
                    onClick = { viewModel.setDarkTheme(true) },
                )
                ThemePreviewCard(
                    label = "Light",
                    selected = !viewModel.isDarkTheme,
                    sidebarColor = Color(0xFFFFFFFF),
                    backgroundColor = Color(0xFFF8FAFC),
                    surfaceColor = Color(0xFFE2E8F0),
                    textColor = Color(0xFF1E293B),
                    onClick = { viewModel.setDarkTheme(false) },
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "INTEGRATIONS",
                style = MaterialTheme.typography.labelMedium,
                color = KdTextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "MCP Server",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Expose all Kubernetes resources via the Model Context Protocol (MCP) for AI tools",
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextSecondary,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Switch(
                    checked = viewModel.isMcpServerEnabled,
                    onCheckedChange = { viewModel.toggleMcpServer(it) },
                )
                Text(
                    if (viewModel.isMcpServerEnabled) {
                        "Running on http://127.0.0.1:${viewModel.mcpServerPort}"
                    } else {
                        "Disabled"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (viewModel.isMcpServerEnabled) MaterialTheme.colorScheme.primary else KdTextSecondary,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Port",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KdTextSecondary,
                )
                OutlinedTextField(
                    value = viewModel.mcpServerPort.toString(),
                    onValueChange = { text ->
                        text.toIntOrNull()?.let { port ->
                            if (port in 1..65535) {
                                viewModel.updateMcpServerPort(port)
                            }
                        }
                    },
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "ABOUT",
                style = MaterialTheme.typography.labelMedium,
                color = KdTextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Application Info",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "View version and application details",
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextSecondary,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showAboutModal = true },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, KdBorder),
            ) {
                Text("About KubeKubeDashDash", color = KdTextPrimary)
            }
        }

        if (showAboutModal) {
            AboutModal(onDismiss = { showAboutModal = false })
        }
    }
}
