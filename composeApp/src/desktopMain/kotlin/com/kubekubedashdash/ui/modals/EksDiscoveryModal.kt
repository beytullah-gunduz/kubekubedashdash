package com.kubekubedashdash.ui.modals

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.ui.modals.viewmodel.ClusterCandidate
import com.kubekubedashdash.ui.modals.viewmodel.EksDiscoveryStep
import com.kubekubedashdash.ui.modals.viewmodel.EksDiscoveryViewModel
import com.kubekubedashdash.ui.modals.viewmodel.ImportRow
import com.kubekubedashdash.ui.modals.viewmodel.ImportRowState
import com.kubekubedashdash.ui.modals.viewmodel.RegionScanRow
import com.kubekubedashdash.ui.modals.viewmodel.RegionScanState
import com.kubekubedashdash.ui.modals.viewmodel.RegionScope

private val EksOrange = Color(0xFFFF9900)

@Composable
fun EksDiscoveryModal(
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
    launchedFromClusterSelector: Boolean = false,
) {
    val reactiveClient = com.kubekubedashdash.ui.LocalReactiveKubeClient.current
    val viewModel: EksDiscoveryViewModel = viewModel { EksDiscoveryViewModel(reactiveClient) }
    val step by viewModel.step.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // After a successful import, every close path (X icon, footer Close) must refresh the
    // parent's cluster list — otherwise the user goes back to a stale list and has to reopen
    // the selector to see what they just imported.
    val closeOrComplete: () -> Unit = {
        if (step == EksDiscoveryStep.DONE && viewModel.anyImportSucceeded) {
            onCompleted()
        } else {
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 640.dp, max = 820.dp),
            shape = RoundedCornerShape(12.dp),
            color = KdSurface,
            border = BorderStroke(1.dp, KdBorder),
            shadowElevation = 24.dp,
        ) {
            Column {
                ModalHeader(step = step, onClose = closeOrComplete)
                HorizontalDivider(color = KdBorder)

                if (!viewModel.awsCliAvailable) {
                    AwsCliMissing(onDismiss)
                } else {
                    when (step) {
                        EksDiscoveryStep.PICK_PROFILE -> ProfileStep(viewModel)
                        EksDiscoveryStep.PICK_REGIONS -> RegionStep(viewModel)
                        EksDiscoveryStep.SCANNING -> ScanningStep(viewModel)
                        EksDiscoveryStep.PICK_CLUSTERS -> ClustersStep(viewModel)
                        EksDiscoveryStep.IMPORTING -> ImportingStep(viewModel)
                        EksDiscoveryStep.DONE -> DoneStep(viewModel, onCompleted)
                    }
                    errorMessage?.let { msg ->
                        HorizontalDivider(color = KdBorder)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, tint = KdError, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(msg, color = KdError, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider(color = KdBorder)
                    Footer(
                        viewModel = viewModel,
                        step = step,
                        busy = busy,
                        onDismiss = closeOrComplete,
                        onCompleted = onCompleted,
                        hideOpenClustersButton = launchedFromClusterSelector,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModalHeader(step: EksDiscoveryStep, onClose: () -> Unit) {
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
                .background(EksOrange.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("EKS", color = EksOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Discover EKS Clusters",
                style = MaterialTheme.typography.titleMedium,
                color = KdTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stepSubtitle(step),
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
            )
        }
        Icon(
            Icons.Default.Close,
            contentDescription = "Close",
            tint = KdTextSecondary,
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClose),
        )
    }
}

private fun stepSubtitle(step: EksDiscoveryStep): String = when (step) {
    EksDiscoveryStep.PICK_PROFILE -> "Step 1 of 4 · Choose AWS profile"
    EksDiscoveryStep.PICK_REGIONS -> "Step 2 of 4 · Choose regions to scan"
    EksDiscoveryStep.SCANNING -> "Step 3 of 4 · Scanning regions"
    EksDiscoveryStep.PICK_CLUSTERS -> "Step 3 of 4 · Choose clusters to import"
    EksDiscoveryStep.IMPORTING -> "Step 4 of 4 · Importing clusters"
    EksDiscoveryStep.DONE -> "Step 4 of 4 · Done"
}

@Composable
private fun AwsCliMissing(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = KdError, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "AWS CLI is not on your PATH.",
                color = KdTextPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Install the AWS CLI v2 and ensure `aws` is reachable from your shell, then try again.",
            color = KdTextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, KdBorder),
            ) { Text("Close", color = KdTextPrimary) }
        }
    }
}

@Composable
private fun ProfileStep(viewModel: EksDiscoveryViewModel) {
    val profiles by viewModel.profiles.collectAsState()
    val selected by viewModel.selectedProfile.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (profiles.isEmpty()) {
            Text(
                "No AWS profiles found at ~/.aws/credentials or ~/.aws/config.",
                color = KdTextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Run `aws configure --profile NAME` from a terminal, then come back.",
                color = KdTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "${profiles.size} profile${if (profiles.size != 1) "s" else ""} detected.",
                color = KdTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.heightIn(max = 360.dp)) {
                LazyColumn {
                    items(profiles, key = { it.name }) { profile ->
                        val isSelected = selected == profile.name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) KdSelected else Color.Transparent)
                                .clickable { viewModel.selectProfile(profile.name) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.selectProfile(profile.name) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = KdPrimary,
                                    unselectedColor = KdTextSecondary,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    profile.name,
                                    color = if (isSelected) KdPrimary else KdTextPrimary,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                val subtitle = buildString {
                                    profile.defaultRegion?.let { append(it) }
                                    if (profile.defaultRegion == null) append("no default region")
                                }
                                Text(
                                    subtitle,
                                    color = KdTextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionStep(viewModel: EksDiscoveryViewModel) {
    val scope by viewModel.regionScope.collectAsState()
    val profile by viewModel.selectedProfile.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val defaultRegion = profiles.firstOrNull { it.name == profile }?.defaultRegion ?: "us-east-1"

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            "Profile: $profile",
            color = KdTextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(12.dp))
        RegionOption(
            label = "Default region only",
            sub = "Just $defaultRegion · fastest",
            selected = scope == RegionScope.DEFAULT_ONLY,
            onClick = { viewModel.setRegionScope(RegionScope.DEFAULT_ONLY) },
        )
        RegionOption(
            label = "Common regions",
            sub = "10 widely-used regions · ~5s",
            selected = scope == RegionScope.COMMON,
            onClick = { viewModel.setRegionScope(RegionScope.COMMON) },
        )
        RegionOption(
            label = "All enabled regions",
            sub = "Every region your account has opted in · slowest",
            selected = scope == RegionScope.ALL_ENABLED,
            onClick = { viewModel.setRegionScope(RegionScope.ALL_ENABLED) },
        )
    }
}

@Composable
private fun RegionOption(label: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) KdSelected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = KdPrimary, unselectedColor = KdTextSecondary),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                label,
                color = if (selected) KdPrimary else KdTextPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(sub, color = KdTextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ScanningStep(viewModel: EksDiscoveryViewModel) {
    val rows by viewModel.scanRows.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            "Scanning ${rows.size} region${if (rows.size != 1) "s" else ""}…",
            color = KdTextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.heightIn(max = 360.dp)) {
            LazyColumn {
                items(rows, key = { it.region }) { row -> ScanRow(row) }
            }
        }
    }
}

@Composable
private fun ScanRow(row: RegionScanRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (val s = row.state) {
                RegionScanState.Pending -> Icon(
                    Icons.Default.HourglassEmpty,
                    null,
                    tint = KdTextSecondary,
                    modifier = Modifier.size(14.dp),
                )

                RegionScanState.Scanning -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = KdPrimary,
                    strokeWidth = 2.dp,
                )

                is RegionScanState.Done -> Icon(
                    Icons.Default.CheckCircle,
                    null,
                    tint = if (s.clusters.isEmpty()) KdTextSecondary else KdSuccess,
                    modifier = Modifier.size(14.dp),
                )

                is RegionScanState.Failed -> Icon(
                    Icons.Default.ErrorOutline,
                    null,
                    tint = KdError,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(row.region, color = KdTextPrimary, modifier = Modifier.width(140.dp))
        when (val s = row.state) {
            RegionScanState.Pending -> Text("waiting", color = KdTextSecondary, style = MaterialTheme.typography.labelSmall)

            RegionScanState.Scanning -> Text("scanning…", color = KdTextSecondary, style = MaterialTheme.typography.labelSmall)

            is RegionScanState.Done -> Text(
                if (s.clusters.isEmpty()) "no clusters" else "${s.clusters.size} cluster${if (s.clusters.size != 1) "s" else ""}",
                color = KdTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )

            is RegionScanState.Failed -> Text(
                s.message,
                color = KdError,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ClustersStep(viewModel: EksDiscoveryViewModel) {
    val candidates by viewModel.candidates.collectAsState()
    val selectedCount = candidates.count { it.selected }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (candidates.isEmpty()) {
            Text("No EKS clusters found.", color = KdTextPrimary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Try widening the region scope, or pick a different AWS profile.",
                color = KdTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$selectedCount of ${candidates.size} selected",
                    color = KdTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "Select all",
                    color = KdPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { viewModel.selectAll(true) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "None",
                    color = KdPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { viewModel.selectAll(false) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.heightIn(max = 360.dp)) {
                LazyColumn {
                    items(candidates, key = { it.cluster.region + "/" + it.cluster.name }) { candidate ->
                        CandidateRow(candidate, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: ClusterCandidate, viewModel: EksDiscoveryViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (candidate.selected) KdHover else Color.Transparent)
            .clickable { viewModel.toggleSelection(candidate.cluster) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = candidate.selected,
            onCheckedChange = { viewModel.toggleSelection(candidate.cluster) },
            colors = CheckboxDefaults.colors(
                checkedColor = KdPrimary,
                uncheckedColor = KdTextSecondary,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(EksOrange),
            contentAlignment = Alignment.Center,
        ) {
            Text("EKS", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                candidate.cluster.name,
                color = KdTextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                candidate.cluster.region,
                color = KdTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (candidate.alreadyImported) {
            Text(
                "already imported",
                color = KdWarning,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ImportingStep(viewModel: EksDiscoveryViewModel) {
    val rows by viewModel.importRows.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            "Importing ${rows.size} cluster${if (rows.size != 1) "s" else ""}…",
            color = KdTextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.heightIn(max = 360.dp)) {
            LazyColumn {
                items(rows, key = { it.cluster.region + "/" + it.cluster.name }) { row -> ImportRowView(row) }
            }
        }
    }
}

@Composable
private fun ImportRowView(row: ImportRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (row.state) {
                ImportRowState.Pending -> Icon(
                    Icons.Default.HourglassEmpty,
                    null,
                    tint = KdTextSecondary,
                    modifier = Modifier.size(14.dp),
                )

                ImportRowState.Importing -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = KdPrimary,
                    strokeWidth = 2.dp,
                )

                is ImportRowState.Done -> Icon(
                    Icons.Default.CheckCircle,
                    null,
                    tint = KdSuccess,
                    modifier = Modifier.size(14.dp),
                )

                is ImportRowState.Failed -> Icon(
                    Icons.Default.ErrorOutline,
                    null,
                    tint = KdError,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${row.cluster.name} · ${row.cluster.region}",
                color = KdTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (val s = row.state) {
                is ImportRowState.Failed -> Text(
                    s.message,
                    color = KdError,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                is ImportRowState.Done -> Text(
                    "imported",
                    color = KdSuccess,
                    style = MaterialTheme.typography.labelSmall,
                )

                else -> {}
            }
        }
    }
}

@Composable
private fun DoneStep(viewModel: EksDiscoveryViewModel, @Suppress("UNUSED_PARAMETER") onCompleted: () -> Unit) {
    val rows by viewModel.importRows.collectAsState()
    val ok = rows.count { it.state is ImportRowState.Done }
    val failed = rows.count { it.state is ImportRowState.Failed }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint = if (failed == 0) {
                KdSuccess
            } else if (ok == 0) {
                KdError
            } else {
                KdWarning
            }
            Icon(Icons.Default.Check, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                summaryLine(ok, failed),
                color = KdTextPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.heightIn(max = 280.dp)) {
            LazyColumn {
                items(rows, key = { it.cluster.region + "/" + it.cluster.name }) { row -> ImportRowView(row) }
            }
        }
    }
}

private fun summaryLine(ok: Int, failed: Int): String = when {
    failed == 0 && ok > 0 -> "$ok cluster${if (ok != 1) "s" else ""} imported."
    ok == 0 && failed > 0 -> "$failed cluster${if (failed != 1) "s" else ""} failed to import."
    else -> "$ok imported, $failed failed."
}

@Composable
private fun Footer(
    viewModel: EksDiscoveryViewModel,
    step: EksDiscoveryStep,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
    hideOpenClustersButton: Boolean,
) {
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val candidates by viewModel.candidates.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step == EksDiscoveryStep.PICK_REGIONS || step == EksDiscoveryStep.PICK_CLUSTERS) {
            OutlinedButton(
                onClick = {
                    viewModel.cancel()
                    viewModel.goToStep(
                        when (step) {
                            EksDiscoveryStep.PICK_REGIONS -> EksDiscoveryStep.PICK_PROFILE
                            EksDiscoveryStep.PICK_CLUSTERS -> EksDiscoveryStep.PICK_REGIONS
                        },
                    )
                },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, KdBorder),
            ) { Text("Back", color = KdTextPrimary) }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = {
                viewModel.cancel()
                onDismiss()
            },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, KdBorder),
            enabled = !busy || step == EksDiscoveryStep.SCANNING || step == EksDiscoveryStep.IMPORTING || step == EksDiscoveryStep.DONE,
        ) {
            Text(if (step == EksDiscoveryStep.DONE) "Close" else "Cancel", color = KdTextPrimary)
        }
        Spacer(Modifier.width(8.dp))

        when (step) {
            EksDiscoveryStep.PICK_PROFILE -> Button(
                onClick = { viewModel.proceedFromProfile() },
                enabled = selectedProfile != null,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
            ) { Text("Next", color = Color.White) }

            EksDiscoveryStep.PICK_REGIONS -> Button(
                onClick = { viewModel.startDiscovery() },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
            ) { Text("Scan", color = Color.White) }

            EksDiscoveryStep.SCANNING -> Button(
                onClick = {},
                enabled = false,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Scanning…", color = Color.White)
            }

            EksDiscoveryStep.PICK_CLUSTERS -> {
                val selected = candidates.count { it.selected }
                Button(
                    onClick = { viewModel.startImport() },
                    enabled = selected > 0,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
                ) { Text(if (selected == 0) "Import" else "Import $selected", color = Color.White) }
            }

            EksDiscoveryStep.IMPORTING -> Button(
                onClick = {},
                enabled = false,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Importing…", color = Color.White)
            }

            EksDiscoveryStep.DONE -> if (!hideOpenClustersButton) {
                Button(
                    onClick = {
                        val anySuccess = viewModel.anyImportSucceeded
                        if (anySuccess) onCompleted() else onDismiss()
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
                ) {
                    Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (viewModel.anyImportSucceeded) "Open clusters" else "Close", color = Color.White)
                }
            }
        }
    }
}
