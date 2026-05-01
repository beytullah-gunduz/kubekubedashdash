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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.ui.screens.settings.viewmodel.SettingsScreenViewModel
import com.kubekubedashdash.util.EksClusterDiscoverer
import org.jetbrains.compose.resources.painterResource

@Composable
fun SettingsScreen(
    onDiscoverEks: () -> Unit = {},
    viewModel: SettingsScreenViewModel = viewModel { SettingsScreenViewModel() },
) {
    val awsCliAvailable = remember { EksClusterDiscoverer.isAwsCliAvailable() }
    var showAboutModal by remember { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isReady = true }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isReady) return@Box
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                "Choose dark, light, or follow your system appearance",
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextSecondary,
            )

            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ThemePreviewCard(
                    label = "Dark",
                    selected = viewModel.themeMode == ThemeMode.DARK,
                    primaryColors = DarkPreviewColors,
                    onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                )
                ThemePreviewCard(
                    label = "Light",
                    selected = viewModel.themeMode == ThemeMode.LIGHT,
                    primaryColors = LightPreviewColors,
                    onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                )
                ThemePreviewCard(
                    label = "System",
                    selected = viewModel.themeMode == ThemeMode.SYSTEM,
                    primaryColors = DarkPreviewColors,
                    secondaryColors = LightPreviewColors,
                    onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
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
                "CLUSTER DISCOVERY",
                style = MaterialTheme.typography.labelMedium,
                color = KdTextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "AWS EKS",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Find EKS clusters in your AWS account and add them to your kubeconfig.",
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextSecondary,
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onDiscoverEks,
                    enabled = awsCliAvailable,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, KdBorder),
                ) {
                    Icon(
                        painterResource(Res.drawable.cloud_filled),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (awsCliAvailable) KdPrimary else KdTextSecondary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Discover EKS Clusters", color = KdTextPrimary)
                }
                if (!awsCliAvailable) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Requires AWS CLI on PATH",
                        color = KdTextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
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
