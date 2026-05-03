package com.kubekubedashdash.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.model.CloseTabFocus
import com.kubekubedashdash.model.TabStripVisibility
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.resources.description_filled
import com.kubekubedashdash.ui.screens.settings.viewmodel.SettingsScreenViewModel
import com.kubekubedashdash.util.EksClusterDiscoverer
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private class SegmentedButtonRowScopeImpl(scope: RowScope) :
    SingleChoiceSegmentedButtonRowScope,
    RowScope by scope

/**
 * Section nav rail on the leading edge of the modal: one row per visible
 * settings section, click to scroll the right pane to that section, with
 * the active section highlighted as the user scrolls.
 */
@Composable
private fun SettingsNavRail(
    sections: List<String>,
    activeSection: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 16.dp, horizontal = 12.dp),
    ) {
        sections.forEach { title ->
            val isActive = title == activeSection
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) KdSelected else Color.Transparent)
                    .clickable { onSelect(title) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isActive) KdTextPrimary else KdTextSecondary,
                )
            }
        }
    }
}

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
private fun SettingsSection(
    title: String,
    onLayoutTop: ((Int) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLayoutTop != null) {
                    Modifier.onGloballyPositioned { coords ->
                        // positionInParent shifts as the parent scrolls; add the
                        // current scroll offset back to recover the absolute layout
                        // offset of the section, so the nav rail can animate-scroll
                        // back to it. The caller threads `scrollState.value` in via
                        // the closure it passes here.
                        onLayoutTop(coords.positionInParent().y.toInt())
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, KdBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable
private fun DemoClusterSimulatorSection(viewModel: SettingsScreenViewModel) {
    val connectedTabs by viewModel.mockConnectedTabs.collectAsState()
    val targets by viewModel.mockTargets.collectAsState()
    val paused by viewModel.mockPaused.collectAsState()
    var showStopAllDialog by remember { mutableStateOf(false) }
    var showKillServerDialog by remember { mutableStateOf(false) }

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
    onClose: () -> Unit = {},
    viewModel: SettingsScreenViewModel = viewModel { SettingsScreenViewModel() },
) {
    val awsCliAvailable = remember { EksClusterDiscoverer.isAwsCliAvailable() }
    var showAboutModal by remember { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isReady = true }
    val mockRunning by viewModel.mockIsRunning.collectAsState()
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Each SettingsSection registers its absolute layout y-offset here as it's
    // measured. The nav rail uses these to (a) know where to scroll on click,
    // and (b) compute which section is currently in view.
    val sectionOffsets = remember { mutableStateMapOf<String, Int>() }
    val sectionTitles = buildList {
        add("Appearance")
        add("Tab behavior")
        add("Integrations")
        add("Cluster discovery")
        if (mockRunning) add("Demo cluster simulator")
        add("Diagnostics")
        add("About")
    }
    val activeSection by remember(sectionTitles) {
        derivedStateOf {
            // The "active" section is the deepest one whose top has scrolled
            // past a small dead-zone at the top of the viewport. Falls back
            // to the first section before the user has scrolled.
            sectionTitles.lastOrNull { (sectionOffsets[it] ?: Int.MAX_VALUE) <= scrollState.value + 24 }
                ?: sectionTitles.firstOrNull()
        }
    }
    fun jumpTo(section: String) {
        sectionOffsets[section]?.let { offset ->
            coroutineScope.launch { scrollState.animateScrollTo(offset) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isReady) return@Box

        Column(modifier = Modifier.fillMaxSize()) {
            // Sticky header — title + close button stay anchored above the
            // scrollable content so users on a long modal don't have to scroll
            // back up to find them.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        painterResource(Res.drawable.close),
                        contentDescription = "Close settings",
                        modifier = Modifier.size(20.dp),
                        tint = KdTextSecondary,
                    )
                }
            }
            HorizontalDivider(color = KdBorder)

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SettingsNavRail(
                    sections = sectionTitles,
                    activeSection = activeSection,
                    onSelect = ::jumpTo,
                    modifier = Modifier.width(160.dp),
                )
                VerticalDivider(color = KdBorder)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                    ) {
                        SettingsSection(
                            title = "Appearance",
                            onLayoutTop = { y -> sectionOffsets["Appearance"] = y + scrollState.value },
                        ) {
                            Text(
                                "Theme",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Choose dark, light, or follow your system appearance",
                                style = MaterialTheme.typography.bodyMedium,
                                color = KdTextSecondary,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.CenterHorizontally)) {
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
                        }

                        Spacer(Modifier.height(16.dp))

                        val closeTabFocus by viewModel.closeTabFocus.collectAsState()

                        SettingsSection(
                            title = "Tab behavior",
                            onLayoutTop = { y -> sectionOffsets["Tab behavior"] = y + scrollState.value },
                        ) {
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

                            Spacer(Modifier.height(20.dp))

                            val tabStripVisibility by viewModel.tabStripVisibility.collectAsState()

                            Text(
                                "Tab strip",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Whether to show the tab strip when only one cluster is open.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = KdTextSecondary,
                            )
                            Spacer(Modifier.height(12.dp))

                            val tabStripOptions = listOf(
                                TabStripVisibility.AUTO to "Compact (only with multiple tabs)",
                                TabStripVisibility.ALWAYS to "Always show",
                            )

                            FullWidthSingleChoiceSegmentedButtonRow {
                                tabStripOptions.forEachIndexed { index, (value, label) ->
                                    SegmentedButton(
                                        selected = tabStripVisibility == value,
                                        onClick = { viewModel.setTabStripVisibility(value) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = tabStripOptions.size,
                                        ),
                                    ) {
                                        Text(label, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        SettingsSection(
                            title = "Integrations",
                            onLayoutTop = { y -> sectionOffsets["Integrations"] = y + scrollState.value },
                        ) {
                            Text(
                                "MCP Server",
                                style = MaterialTheme.typography.titleMedium,
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
                        }

                        Spacer(Modifier.height(16.dp))

                        SettingsSection(
                            title = "Cluster discovery",
                            onLayoutTop = { y -> sectionOffsets["Cluster discovery"] = y + scrollState.value },
                        ) {
                            Text(
                                "AWS EKS",
                                style = MaterialTheme.typography.titleMedium,
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
                        }

                        if (mockRunning) {
                            Spacer(Modifier.height(16.dp))
                            SettingsSection(
                                title = "Demo cluster simulator",
                                onLayoutTop = { y -> sectionOffsets["Demo cluster simulator"] = y + scrollState.value },
                            ) {
                                DemoClusterSimulatorSection(viewModel)
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        SettingsSection(
                            title = "Diagnostics",
                            onLayoutTop = { y -> sectionOffsets["Diagnostics"] = y + scrollState.value },
                        ) {
                            Text(
                                "Application logs",
                                style = MaterialTheme.typography.titleMedium,
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
                        }

                        Spacer(Modifier.height(16.dp))

                        SettingsSection(
                            title = "About",
                            onLayoutTop = { y -> sectionOffsets["About"] = y + scrollState.value },
                        ) {
                            Text(
                                "Application info",
                                style = MaterialTheme.typography.titleMedium,
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
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }

        if (showAboutModal) {
            AboutModal(onDismiss = { showAboutModal = false })
        }
    }
}
