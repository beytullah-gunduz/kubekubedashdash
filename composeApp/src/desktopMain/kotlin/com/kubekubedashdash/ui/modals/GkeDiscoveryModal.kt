package com.kubekubedashdash.ui.modals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
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
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_circle_filled
import com.kubekubedashdash.resources.check_filled
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.resources.error
import com.kubekubedashdash.resources.hourglass_empty_filled
import com.kubekubedashdash.resources.search_filled
import com.kubekubedashdash.resources.warning_filled
import com.kubekubedashdash.ui.modals.viewmodel.GkeClusterCandidate
import com.kubekubedashdash.ui.modals.viewmodel.GkeDiscoveryStep
import com.kubekubedashdash.ui.modals.viewmodel.GkeDiscoveryViewModel
import com.kubekubedashdash.ui.modals.viewmodel.GkeImportRow
import com.kubekubedashdash.ui.modals.viewmodel.GkeImportRowState
import com.kubekubedashdash.ui.modals.viewmodel.ProjectLoadState
import com.kubekubedashdash.ui.modals.viewmodel.ProjectScanRow
import com.kubekubedashdash.ui.modals.viewmodel.ProjectScanState
import com.kubekubedashdash.util.GcpProject
import com.kubekubedashdash.util.ShellEnvironment
import org.jetbrains.compose.resources.painterResource

private val GkeBlue = Color(0xFF4285F4) // D8

@Composable
fun GkeDiscoveryModal(
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
    launchedFromClusterSelector: Boolean = false,
) {
    val reactiveClient = com.kubekubedashdash.ui.LocalReactiveKubeClient.current
    val viewModel: GkeDiscoveryViewModel = viewModel { GkeDiscoveryViewModel(reactiveClient) }
    LaunchedEffect(Unit) { viewModel.reset() }
    val step by viewModel.step.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // After a successful import, every close path (X icon, footer Close) must refresh the
    // parent's cluster list — otherwise the user goes back to a stale list and has to reopen
    // the selector to see what they just imported.
    val closeOrComplete: () -> Unit = {
        if (step == GkeDiscoveryStep.DONE && viewModel.anyImportSucceeded) {
            onCompleted()
        } else {
            onDismiss()
        }
    }

    val modalFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { modalFocus.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(modalFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    closeOrComplete()
                    true
                } else {
                    false
                }
            }
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

                if (!viewModel.gcloudCliAvailable) {
                    GcloudMissing(onDismiss)
                } else {
                    when (step) {
                        GkeDiscoveryStep.PICK_PROJECTS -> ProjectStep(viewModel)
                        GkeDiscoveryStep.SCANNING -> ScanningStep(viewModel)
                        GkeDiscoveryStep.PICK_CLUSTERS -> ClustersStep(viewModel)
                        GkeDiscoveryStep.IMPORTING -> ImportingStep(viewModel)
                        GkeDiscoveryStep.DONE -> DoneStep(viewModel, onCompleted)
                    }
                    errorMessage?.let { msg ->
                        HorizontalDivider(color = KdBorder)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(Res.drawable.error), null, tint = KdError, modifier = Modifier.size(16.dp))
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
private fun ModalHeader(step: GkeDiscoveryStep, onClose: () -> Unit) {
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
                .background(GkeBlue.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("GKE", color = GkeBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Discover GKE Clusters",
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
            painterResource(Res.drawable.close_filled),
            contentDescription = "Close",
            tint = KdTextSecondary,
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClose),
        )
    }
}

private fun stepSubtitle(step: GkeDiscoveryStep): String = when (step) {
    GkeDiscoveryStep.PICK_PROJECTS -> "Step 1 of 3 · Choose GCP projects"
    GkeDiscoveryStep.SCANNING -> "Step 2 of 3 · Scanning projects"
    GkeDiscoveryStep.PICK_CLUSTERS -> "Step 2 of 3 · Choose clusters to import"
    GkeDiscoveryStep.IMPORTING -> "Step 3 of 3 · Importing clusters"
    GkeDiscoveryStep.DONE -> "Step 3 of 3 · Done"
}

@Composable
private fun GcloudMissing(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.error), null, tint = KdError, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Google Cloud SDK not found",
                color = KdTextPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Install the Google Cloud SDK and ensure `gcloud` is reachable from your shell, then try again.",
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
private fun ProjectStep(viewModel: GkeDiscoveryViewModel) {
    val loadState by viewModel.projectLoadState.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        when (val state = loadState) {
            ProjectLoadState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = KdPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking gcloud sign-in…", color = KdTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            ProjectLoadState.NotSignedIn -> NotSignedIn(viewModel)

            is ProjectLoadState.Failed -> LoadFailed(state.message, viewModel)

            is ProjectLoadState.Loaded -> LoadedProjects(state.projects, viewModel)
        }
    }
}

@Composable
private fun NotSignedIn(viewModel: GkeDiscoveryViewModel) {
    Text(
        "No active Google Cloud account found.",
        color = KdTextPrimary,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Sign in from a terminal, then try again:",
        color = KdTextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "gcloud auth login",
        color = KdTextPrimary,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(KdSurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = { viewModel.retryLoad() },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, KdBorder),
    ) { Text("Try again", color = KdTextPrimary) }
}

@Composable
private fun LoadFailed(message: String, viewModel: GkeDiscoveryViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(Res.drawable.error), null, tint = KdError, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Could not list GCP projects.", color = KdTextPrimary, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(6.dp))
    Text(message, color = KdTextSecondary, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = { viewModel.retryLoad() },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, KdBorder),
    ) { Text("Try again", color = KdTextPrimary) }
}

@Composable
private fun LoadedProjects(projects: List<GcpProject>, viewModel: GkeDiscoveryViewModel) {
    val selected by viewModel.selectedProjects.collectAsState()
    val filter by viewModel.projectFilter.collectAsState()
    val notice by viewModel.selectionNotice.collectAsState()

    if (projects.isEmpty()) {
        Text(
            "No active GCP projects found for this account.",
            color = KdTextPrimary,
            fontWeight = FontWeight.Medium,
        )
        return
    }

    val visible = if (filter.isBlank()) {
        projects
    } else {
        projects.filter {
            it.projectId.contains(filter, ignoreCase = true) || it.displayName.contains(filter, ignoreCase = true)
        }
    }

    ProjectSearchField(value = filter, onValueChange = { viewModel.setProjectFilter(it) })
    Spacer(Modifier.height(10.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${selected.size} of ${projects.size} selected",
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
                .clickable { viewModel.selectAllProjects(true) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "None",
            color = KdPrimary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { viewModel.selectAllProjects(false) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    notice?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = KdWarning, style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(8.dp))
    val listState = rememberLazyListState()
    Box(modifier = Modifier.heightIn(max = 360.dp)) {
        LazyColumn(state = listState) {
            items(visible, key = { it.projectId }) { project ->
                val isSelected = project.projectId in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) KdSelected else Color.Transparent)
                        .clickable { viewModel.toggleProject(project.projectId) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { viewModel.toggleProject(project.projectId) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = KdPrimary,
                            uncheckedColor = KdTextSecondary,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            project.displayName,
                            color = if (isSelected) KdPrimary else KdTextPrimary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            project.projectId,
                            color = KdTextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun ProjectSearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Filter projects by id or name…", style = MaterialTheme.typography.bodySmall) },
        leadingIcon = {
            Icon(
                painterResource(Res.drawable.search_filled),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = KdTextSecondary,
            )
        },
        textStyle = MaterialTheme.typography.bodySmall,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KdPrimary,
            unfocusedBorderColor = KdBorder,
            focusedTextColor = KdTextPrimary,
            unfocusedTextColor = KdTextPrimary,
        ),
    )
}

@Composable
private fun ScanningStep(viewModel: GkeDiscoveryViewModel) {
    val rows by viewModel.scanRows.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            "Scanning ${rows.size} project${if (rows.size != 1) "s" else ""}…",
            color = KdTextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(12.dp))
        val listState = rememberLazyListState()
        Box(modifier = Modifier.heightIn(max = 360.dp)) {
            LazyColumn(state = listState) {
                items(rows, key = { it.projectId }) { row -> ScanRow(row) }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ScanRow(row: ProjectScanRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (val s = row.state) {
                ProjectScanState.Pending -> Icon(
                    painterResource(Res.drawable.hourglass_empty_filled),
                    null,
                    tint = KdTextSecondary,
                    modifier = Modifier.size(14.dp),
                )

                ProjectScanState.Scanning -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = KdPrimary,
                    strokeWidth = 2.dp,
                )

                is ProjectScanState.Done -> Icon(
                    painterResource(Res.drawable.check_circle_filled),
                    null,
                    tint = if (s.clusters.isEmpty()) KdTextSecondary else KdSuccess,
                    modifier = Modifier.size(14.dp),
                )

                is ProjectScanState.Failed -> Icon(
                    painterResource(Res.drawable.error),
                    null,
                    tint = KdError,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            row.projectId,
            color = KdTextPrimary,
            modifier = Modifier.width(200.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (val s = row.state) {
            ProjectScanState.Pending -> Text("waiting", color = KdTextSecondary, style = MaterialTheme.typography.labelSmall)

            ProjectScanState.Scanning -> Text("scanning…", color = KdTextSecondary, style = MaterialTheme.typography.labelSmall)

            is ProjectScanState.Done -> Text(
                if (s.clusters.isEmpty()) "no clusters" else "${s.clusters.size} cluster${if (s.clusters.size != 1) "s" else ""}",
                color = KdTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )

            is ProjectScanState.Failed -> Text(
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
private fun ClustersStep(viewModel: GkeDiscoveryViewModel) {
    val candidates by viewModel.candidates.collectAsState()
    val selectedCount = candidates.count { it.selected }

    // D7: the gke-gcloud-auth-plugin check is advisory, not blocking. Surface it here
    // because the diagnostics modal is unreachable once the user already has contexts.
    val authPluginMissing = remember { ShellEnvironment.resolveCommand("gke-gcloud-auth-plugin") == null }

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (authPluginMissing) {
            AuthPluginWarningBanner()
            Spacer(Modifier.height(12.dp))
        }

        if (candidates.isEmpty()) {
            Text("No GKE clusters found.", color = KdTextPrimary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Try selecting different GCP projects.",
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
            val listState = rememberLazyListState()
            Box(modifier = Modifier.heightIn(max = 360.dp)) {
                LazyColumn(state = listState) {
                    items(
                        candidates,
                        key = { it.cluster.projectId + "/" + it.cluster.location + "/" + it.cluster.name },
                    ) { candidate ->
                        CandidateRow(candidate, viewModel)
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun AuthPluginWarningBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(KdWarning.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(Res.drawable.warning_filled), null, tint = KdWarning, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "GKE auth plugin not found — imported clusters will fail to connect. " +
                "Install with: gcloud components install gke-gcloud-auth-plugin",
            color = KdTextPrimary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun CandidateRow(candidate: GkeClusterCandidate, viewModel: GkeDiscoveryViewModel) {
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
                .background(GkeBlue),
            contentAlignment = Alignment.Center,
        ) {
            Text("GKE", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
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
            // GKE clusters can be PROVISIONING, RECONCILING, STOPPING, ERROR or DEGRADED —
            // surface a non-RUNNING status so the user isn't offered an unhealthy cluster as
            // if it were healthy, without filtering or disabling the row.
            val status = candidate.cluster.status
            val subtitle = if (status != null && status != "RUNNING") {
                "${candidate.cluster.projectId} · ${candidate.cluster.location} · $status"
            } else {
                "${candidate.cluster.projectId} · ${candidate.cluster.location}"
            }
            Text(
                subtitle,
                color = KdTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
private fun ImportingStep(viewModel: GkeDiscoveryViewModel) {
    val rows by viewModel.importRows.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            "Importing ${rows.size} cluster${if (rows.size != 1) "s" else ""}…",
            color = KdTextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(12.dp))
        val listState = rememberLazyListState()
        Box(modifier = Modifier.heightIn(max = 360.dp)) {
            LazyColumn(state = listState) {
                items(
                    rows,
                    key = { it.cluster.projectId + "/" + it.cluster.location + "/" + it.cluster.name },
                ) { row -> ImportRowView(row) }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ImportRowView(row: GkeImportRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (row.state) {
                GkeImportRowState.Pending -> Icon(
                    painterResource(Res.drawable.hourglass_empty_filled),
                    null,
                    tint = KdTextSecondary,
                    modifier = Modifier.size(14.dp),
                )

                GkeImportRowState.Importing -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = KdPrimary,
                    strokeWidth = 2.dp,
                )

                is GkeImportRowState.Done -> Icon(
                    painterResource(Res.drawable.check_circle_filled),
                    null,
                    tint = KdSuccess,
                    modifier = Modifier.size(14.dp),
                )

                is GkeImportRowState.Failed -> Icon(
                    painterResource(Res.drawable.error),
                    null,
                    tint = KdError,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${row.cluster.projectId} · ${row.cluster.name} · ${row.cluster.location}",
                color = KdTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (val s = row.state) {
                is GkeImportRowState.Failed -> Text(
                    s.message,
                    color = KdError,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                is GkeImportRowState.Done -> Text(
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
private fun DoneStep(viewModel: GkeDiscoveryViewModel, @Suppress("UNUSED_PARAMETER") onCompleted: () -> Unit) {
    val rows by viewModel.importRows.collectAsState()
    val backupPath by viewModel.backupPath.collectAsState()
    val ok = rows.count { it.state is GkeImportRowState.Done }
    val failed = rows.count { it.state is GkeImportRowState.Failed }
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
            Icon(painterResource(Res.drawable.check_filled), null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                summaryLine(ok, failed),
                color = KdTextPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
        backupPath?.let { path ->
            Spacer(Modifier.height(6.dp))
            Text(
                "Your previous kubeconfig was backed up to $path",
                color = KdTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        val listState = rememberLazyListState()
        Box(modifier = Modifier.heightIn(max = 280.dp)) {
            LazyColumn(state = listState) {
                items(
                    rows,
                    key = { it.cluster.projectId + "/" + it.cluster.location + "/" + it.cluster.name },
                ) { row -> ImportRowView(row) }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
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
    viewModel: GkeDiscoveryViewModel,
    step: GkeDiscoveryStep,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
    hideOpenClustersButton: Boolean,
) {
    val selectedProjects by viewModel.selectedProjects.collectAsState()
    val exceedsCap by viewModel.projectSelectionExceedsCap.collectAsState()
    val candidates by viewModel.candidates.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step == GkeDiscoveryStep.PICK_CLUSTERS) {
            OutlinedButton(
                onClick = {
                    viewModel.cancel()
                    viewModel.goToStep(GkeDiscoveryStep.PICK_PROJECTS)
                },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, KdBorder),
            ) { Text("Back", color = KdTextPrimary) }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = {
                // Don't reset state on DONE: onDismiss is closeOrComplete, which reads
                // `step` and `anyImportSucceeded` to decide whether to fire onCompleted
                // (refreshes the parent's context list). cancel() would clear both.
                if (step != GkeDiscoveryStep.DONE) viewModel.cancel()
                onDismiss()
            },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, KdBorder),
            enabled = !busy || step == GkeDiscoveryStep.SCANNING || step == GkeDiscoveryStep.IMPORTING || step == GkeDiscoveryStep.DONE,
        ) {
            Text(if (step == GkeDiscoveryStep.DONE) "Close" else "Cancel", color = KdTextPrimary)
        }
        Spacer(Modifier.width(8.dp))

        when (step) {
            GkeDiscoveryStep.PICK_PROJECTS -> Button(
                onClick = { viewModel.startScan() },
                enabled = selectedProjects.isNotEmpty() && !exceedsCap,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
            ) { Text("Scan", color = Color.White) }

            GkeDiscoveryStep.SCANNING -> Button(
                onClick = {},
                enabled = false,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Scanning…", color = Color.White)
            }

            GkeDiscoveryStep.PICK_CLUSTERS -> {
                val selected = candidates.count { it.selected }
                Button(
                    onClick = { viewModel.startImport() },
                    enabled = selected > 0,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
                ) { Text(if (selected == 0) "Import" else "Import $selected", color = Color.White) }
            }

            GkeDiscoveryStep.IMPORTING -> Button(
                onClick = {},
                enabled = false,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Importing…", color = Color.White)
            }

            GkeDiscoveryStep.DONE -> if (!hideOpenClustersButton) {
                Button(
                    onClick = {
                        val anySuccess = viewModel.anyImportSucceeded
                        if (anySuccess) onCompleted() else onDismiss()
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KdPrimary),
                ) {
                    Icon(painterResource(Res.drawable.cloud_filled), null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (viewModel.anyImportSucceeded) "Open clusters" else "Close", color = Color.White)
                }
            }
        }
    }
}
