package com.kubedash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubedash.KubeClient
import com.kubedash.KdBorder
import com.kubedash.KdPrimary
import com.kubedash.KdSurface
import com.kubedash.KdSurfaceVariant
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.ui.LabelChip
import com.kubedash.ui.ResourceLoadingIndicator
import com.kubedash.ui.StatusBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Data class for key-value rows in the overview ───────────────────────────────

data class DetailField(
    val label: String,
    val value: String,
    val valueColor: Color? = null,
)

class ExtraTab(
    val label: String,
    val icon: ImageVector,
    val content: @Composable () -> Unit,
)

@Composable
fun ResourceDetailPanel(
    kind: String,
    name: String,
    namespace: String?,
    status: String?,
    fields: List<DetailField>,
    labels: Map<String, String>,
    kubeClient: KubeClient,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    extraTabs: List<ExtraTab> = emptyList(),
) {
    var activeTab by remember { mutableIntStateOf(0) }
    LaunchedEffect(name, namespace) { activeTab = 0 }

    data class TabDef(val label: String, val icon: ImageVector)
    val tabs = buildList {
        add(TabDef("Overview", Icons.Default.Info))
        extraTabs.forEach { add(TabDef(it.label, it.icon)) }
        add(TabDef("YAML", Icons.Default.Code))
    }
    val yamlIndex = tabs.lastIndex

    Surface(modifier = modifier, color = KdSurface) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KdSurfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = KdTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (status != null) StatusBadge(status)
                        Text(
                            buildString {
                                append(kind)
                                if (namespace != null) append(" · $namespace")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "Close", Modifier.size(16.dp), tint = KdTextSecondary)
                }
            }

            // Tabs
            SecondaryTabRow(
                selectedTabIndex = activeTab,
                containerColor = KdSurfaceVariant.copy(alpha = 0.5f),
                contentColor = KdPrimary,
                divider = { HorizontalDivider(color = KdBorder) },
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == activeTab,
                        onClick = { activeTab = index },
                        selectedContentColor = KdPrimary,
                        unselectedContentColor = KdTextSecondary,
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(tab.icon, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(tab.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            when {
                activeTab == 0 -> GenericOverviewTab(fields, labels)
                activeTab == yamlIndex -> GenericYamlTab(kind, name, namespace, kubeClient)
                else -> extraTabs[activeTab - 1].content()
            }
        }
    }
}

// ── Overview Tab ────────────────────────────────────────────────────────────────

@Composable
private fun GenericOverviewTab(fields: List<DetailField>, labels: Map<String, String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (fields.isNotEmpty()) {
            Text("Details", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    fields.forEach { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(f.label, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                            if (f.valueColor != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(f.valueColor))
                                    Spacer(Modifier.width(5.dp))
                                    Text(f.value, style = MaterialTheme.typography.bodySmall, color = f.valueColor, fontWeight = FontWeight.Medium)
                                }
                            } else {
                                Text(f.value, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        if (labels.isNotEmpty()) {
            Text("Labels", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.entries.toList().chunked(2).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chunk.forEach { (k, v) -> LabelChip(k, v) }
                    }
                }
            }
        }
    }
}

// ── YAML Tab ────────────────────────────────────────────────────────────────────

@Composable
private fun GenericYamlTab(kind: String, name: String, namespace: String?, kubeClient: KubeClient) {
    var yaml by remember(kind, name, namespace) { mutableStateOf<String?>(null) }
    var loading by remember(kind, name, namespace) { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(kind, name, namespace) {
        loading = true
        yaml = withContext(Dispatchers.IO) { kubeClient.getResourceYaml(kind, name, namespace) }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { yaml?.let { clipboardManager.setText(AnnotatedString(it)) } },
                colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (loading) {
            ResourceLoadingIndicator()
        } else {
            val lines = (yaml ?: "").lines()
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(lines.size) { i ->
                    Row {
                        Text(
                            "${i + 1}",
                            modifier = Modifier.width(36.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                            color = KdTextSecondary.copy(alpha = 0.35f),
                        )
                        Text(
                            text = highlightYamlLine(lines[i]),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}
