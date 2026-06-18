package com.kubekubedashdash.ui.screens

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.code_filled
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.delete_filled
import com.kubekubedashdash.resources.info_filled
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.KeyValueChipFlow
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.components.TooltipIconButton
import com.kubekubedashdash.ui.components.parseMapSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// ── Data class for key-value rows in the overview ───────────────────────────────

data class DetailField(
    val label: String,
    val value: String,
    val valueColor: Color? = null,
)

/**
 * An action button rendered in the [ResourceDetailPanel] header alongside Delete.
 *
 * @param label       Button tooltip / accessibility label.
 * @param icon        Icon to display (16 dp).
 * @param destructive When true the icon renders in [KdError] red; otherwise [KdTextSecondary].
 * @param enabled     Controls whether the button responds to clicks.
 * @param onClick     Invoked when the button is clicked.
 */
data class DetailAction(
    val label: String,
    val icon: DrawableResource,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val tint: Color? = null,
    val description: String? = null,
    val onClick: () -> Unit,
)

class ExtraTab(
    val label: String,
    val icon: DrawableResource,
    val badgeCount: Int? = null,
    val isLoading: Boolean = false,
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
    annotations: Map<String, String>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    extraTabs: List<ExtraTab> = emptyList(),
    labelQuery: String = "",
    onToggleLabel: (String, String) -> Unit = { _, _ -> },
    annotationQuery: String = "",
    onToggleAnnotation: (String, String) -> Unit = { _, _ -> },
    apiGroup: String? = null,
    apiVersion: String? = null,
    plural: String? = null,
    onDelete: (() -> Unit)? = null,
    actions: List<DetailAction> = emptyList(),
) {
    var activeTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    data class TabDef(val label: String, val icon: DrawableResource, val badgeCount: Int? = null, val isLoading: Boolean = false)
    val tabs = buildList {
        add(TabDef("Overview", Res.drawable.info_filled))
        extraTabs.forEach { add(TabDef(it.label, it.icon, it.badgeCount, it.isLoading)) }
        add(TabDef("YAML", Res.drawable.code_filled))
    }
    val yamlIndex = tabs.lastIndex
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    LaunchedEffect(name, namespace) {
        activeTab = 0
        pagerState.scrollToPage(0)
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { activeTab = it }
    }

    Surface(modifier = modifier, color = KdSurface) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            DetailPanelHeader(
                name = name,
                subtitle = buildString {
                    append(kind)
                    if (namespace != null) append(" · $namespace")
                },
                status = status,
                actions = actions,
                onDelete = onDelete,
                onClose = onClose,
            )

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
                        onClick = {
                            activeTab = index
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        selectedContentColor = KdPrimary,
                        unselectedContentColor = KdTextSecondary,
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(tab.icon), null, Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(tab.label, style = MaterialTheme.typography.labelMedium)
                            if (tab.isLoading) {
                                Spacer(Modifier.width(6.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = KdTextSecondary,
                                )
                            } else if (tab.badgeCount != null && tab.badgeCount > 0) {
                                Spacer(Modifier.width(4.dp))
                                Badge {
                                    Text(
                                        tab.badgeCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when {
                    page == 0 -> GenericOverviewTab(
                        fields = fields,
                        labels = labels,
                        annotations = annotations,
                        labelQuery = labelQuery,
                        onToggleLabel = onToggleLabel,
                        annotationQuery = annotationQuery,
                        onToggleAnnotation = onToggleAnnotation,
                    )

                    page == yamlIndex -> GenericYamlTab(kind, name, namespace, apiGroup, apiVersion, plural)

                    else -> extraTabs[page - 1].content()
                }
            }
        }
    }
}

// ── Shared panel header ─────────────────────────────────────────────────────────

@Composable
fun DetailPanelHeader(
    name: String,
    subtitle: String,
    status: String?,
    actions: List<DetailAction> = emptyList(),
    onDelete: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(KdSurfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status != null) StatusBadge(status)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
            }
        }
        actions.forEach { action ->
            TooltipIconButton(icon = action.icon, label = action.label, tint = action.tint ?: if (action.destructive) KdError else KdTextSecondary, description = action.description, enabled = action.enabled, onClick = action.onClick)
        }
        if (onDelete != null) {
            TooltipIconButton(Res.drawable.delete_filled, "Delete", KdError, description = "Permanently remove this resource — it won't come back unless recreated.", onClick = onDelete)
        }
        TooltipIconButton(Res.drawable.close_filled, "Close", KdTextSecondary, onClick = onClose)
    }
}

// ── Shared detail card ──────────────────────────────────────────────────────────

@Composable
fun DetailFieldsCard(fields: List<DetailField>, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
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

// ── Overview Tab ────────────────────────────────────────────────────────────────

@Composable
private fun GenericOverviewTab(
    fields: List<DetailField>,
    labels: Map<String, String>,
    annotations: Map<String, String>,
    labelQuery: String,
    onToggleLabel: (String, String) -> Unit,
    annotationQuery: String,
    onToggleAnnotation: (String, String) -> Unit,
) {
    val activeLabels = remember(labelQuery) { parseMapSelector(labelQuery) }
    val activeAnnotations = remember(annotationQuery) { parseMapSelector(annotationQuery) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (fields.isNotEmpty()) {
            Text("Details", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            DetailFieldsCard(fields = fields)
        }

        if (labels.isNotEmpty()) {
            Text("Labels", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            KeyValueChipFlow(
                entries = labels,
                activeFilter = activeLabels,
                onToggle = onToggleLabel,
            )
        }

        if (annotations.isNotEmpty()) {
            Text("Annotations", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            KeyValueChipFlow(
                entries = annotations,
                activeFilter = activeAnnotations,
                onToggle = onToggleAnnotation,
            )
        }
    }
}

// ── YAML Tab ────────────────────────────────────────────────────────────────────

@Composable
internal fun GenericYamlTab(
    kind: String,
    name: String,
    namespace: String?,
    apiGroup: String? = null,
    apiVersion: String? = null,
    plural: String? = null,
) {
    val kubeClient = LocalReactiveKubeClient.current
    var yaml by remember(kind, name, namespace) { mutableStateOf<String?>(null) }
    var loading by remember(kind, name, namespace) { mutableStateOf(true) }
    LaunchedEffect(kind, name, namespace, apiGroup, apiVersion) {
        loading = true
        yaml = withContext(Dispatchers.IO) {
            kubeClient.getResourceYaml(kind, name, namespace, apiGroup, apiVersion, plural)
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { yaml?.let { text -> copyToClipboard(text) } },
                colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Icon(painterResource(Res.drawable.content_copy_filled), null, Modifier.size(13.dp))
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
                                fontFamily = kdMonoFamily(),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                            color = KdTextSecondary.copy(alpha = 0.35f),
                        )
                        Text(
                            text = highlightYamlLine(lines[i]),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = kdMonoFamily(),
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

private fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
