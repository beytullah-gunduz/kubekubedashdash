package com.kubekubedashdash.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.model.CloseTabFocus
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.resources.description_filled
import com.kubekubedashdash.ui.screens.settings.viewmodel.SettingsScreenViewModel
import com.kubekubedashdash.util.EksClusterDiscoverer
import org.jetbrains.compose.resources.painterResource

private class SegmentedButtonRowScopeImpl(scope: RowScope) :
    SingleChoiceSegmentedButtonRowScope,
    RowScope by scope

// M3's SingleChoiceSegmentedButtonRow uses width(IntrinsicSize.Min) which collapses to the
// sum of each button's min-intrinsic width; equal weight(1f) then gives each button 1/N of
// that sum, starving the widest label. This wrapper uses fillMaxWidth() instead so each
// button gets 1/N of the actual container width — always enough on a desktop settings pane.
@Composable
private fun FullWidthSingleChoiceSegmentedButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(-SegmentedButtonDefaults.BorderWidth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val scope = remember { SegmentedButtonRowScopeImpl(this) }
        scope.content()
    }
}

@Composable
private fun DemoClusterSimulatorSection(viewModel: SettingsScreenViewModel) {
    val connectedTabs by viewModel.mockConnectedTabs.collectAsState()
    val targets by viewModel.mockTargets.collectAsState()
    val paused by viewModel.mockPaused.collectAsState()
    var showStopAllDialog by remember { mutableStateOf(false) }
    var showKillServerDialog by remember { mutableStateOf(false) }

    Text(
        "DEMO CLUSTER SIMULATOR",
        style = MaterialTheme.typography.labelMedium,
        color = KdTextSecondary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )

    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("●", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodyMedium)
        Text(
            "Running — $connectedTabs ${if (connectedTabs == 1) "tab" else "tabs"} connected",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }

    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = paused, onCheckedChange = { viewModel.setMockPaused(it) })
        Text(
            if (paused) "Simulation paused" else "Simulation running",
            style = MaterialTheme.typography.bodyMedium,
            color = KdTextSecondary,
        )
    }

    Spacer(Modifier.height(20.dp))

    Text("Node range", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(4.dp))
    Text(
        "Min ${targets.nodesMin}  ·  Max ${targets.nodesMax}",
        style = MaterialTheme.typography.bodySmall,
        color = KdTextSecondary,
    )
    Spacer(Modifier.height(4.dp))
    RangeSlider(
        value = targets.nodesMin.toFloat()..targets.nodesMax.toFloat(),
        onValueChange = { range ->
            val lo = range.start.toInt().coerceIn(1, 100)
            val hi = range.endInclusive.toInt().coerceIn(lo, 100)
            viewModel.setMockTargets(targets.copy(nodesMin = lo, nodesMax = hi))
        },
        valueRange = 1f..100f,
        steps = 98,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(20.dp))

    Text("Pod range", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(4.dp))
    Text(
        "Min ${targets.podsMin}  ·  Max ${targets.podsMax}",
        style = MaterialTheme.typography.bodySmall,
        color = KdTextSecondary,
    )
    Spacer(Modifier.height(4.dp))
    RangeSlider(
        value = targets.podsMin.toFloat()..targets.podsMax.toFloat(),
        onValueChange = { range ->
            val lo = range.start.toInt().coerceIn(10, 1000)
            val hi = range.endInclusive.toInt().coerceIn(lo, 1000)
            viewModel.setMockTargets(targets.copy(podsMin = lo, podsMax = hi))
        },
        valueRange = 10f..1000f,
        steps = 989,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(20.dp))

    Text("Chaos", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { showStopAllDialog = true },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, KdBorder),
        ) {
            Text("Stop all nodes & pods", color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = { showKillServerDialog = true },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, KdBorder),
        ) {
            Text("Kill mock server", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showStopAllDialog) {
        AlertDialog(
            onDismissRequest = { showStopAllDialog = false },
            title = { Text("Stop all nodes & pods?") },
            text = {
                Text("Delete every node and pod in the demo cluster, including the protected baseline. The simulator will start rebuilding immediately.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.stopAllMockResources()
                        showStopAllDialog = false
                    },
                ) {
                    Text("Stop all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopAllDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showKillServerDialog) {
        AlertDialog(
            onDismissRequest = { showKillServerDialog = false },
            title = { Text("Kill mock server?") },
            text = {
                Text("Shut down the mock Kubernetes server. Open demo tabs will show a connection error until you reconnect.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.killMockServer()
                        showKillServerDialog = false
                    },
                ) {
                    Text("Kill server", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showKillServerDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun SettingsScreen(
    onDiscoverEks: () -> Unit = {},
    onOpenLogsTab: () -> Unit = {},
    viewModel: SettingsScreenViewModel = viewModel { SettingsScreenViewModel() },
) {
    val awsCliAvailable = remember { EksClusterDiscoverer.isAwsCliAvailable() }
    var showAboutModal by remember { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isReady = true }
    val mockRunning by viewModel.mockIsRunning.collectAsState()

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

            val closeTabFocus by viewModel.closeTabFocus.collectAsState()

            Text(
                "TAB BEHAVIOR",
                style = MaterialTheme.typography.labelMedium,
                color = KdTextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "When closing the active tab, focus:",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(12.dp))

            val tabFocusOptions = listOf(
                CloseTabFocus.FIRST to "First tab",
                CloseTabFocus.LEFT_NEIGHBOR to "Left neighbor",
                CloseTabFocus.PREVIOUS_ACTIVE to "Last used",
            )

            FullWidthSingleChoiceSegmentedButtonRow {
                tabFocusOptions.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = closeTabFocus == value,
                        onClick = { viewModel.setCloseTabFocus(value) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = tabFocusOptions.size,
                        ),
                    ) {
                        Text(label, maxLines = 1, softWrap = false)
                    }
                }
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

            if (mockRunning) {
                Spacer(Modifier.height(32.dp))
                DemoClusterSimulatorSection(viewModel)
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "DIAGNOSTICS",
                style = MaterialTheme.typography.labelMedium,
                color = KdTextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Application logs",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Open the in-app log viewer in a new tab to inspect application events.",
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextSecondary,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenLogsTab,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, KdBorder),
            ) {
                Icon(
                    painterResource(Res.drawable.description_filled),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = KdPrimary,
                )
                Spacer(Modifier.width(6.dp))
                Text("Open application logs", color = KdTextPrimary)
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
