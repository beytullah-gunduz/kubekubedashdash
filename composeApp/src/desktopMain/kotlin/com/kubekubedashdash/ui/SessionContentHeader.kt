package com.kubekubedashdash.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPlaceholder
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.Screen
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_filled
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.expand_more_filled
import com.kubekubedashdash.resources.folder_special_filled
import com.kubekubedashdash.resources.search_filled
import org.jetbrains.compose.resources.painterResource

private val sessionHeaderIsMacOS: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/**
 * Per-tab toolbar above the resource list (right of the sidebar). Hosts the
 * namespace filter and search — controls scoped to *this* cluster tab, so they
 * live with the data they act on instead of in the window-global title bar.
 *
 * The namespace selector is shown only for namespaced resource lists; on
 * cluster-scoped screens (Nodes, PVs, Cluster overview, the Namespaces list
 * itself, non-namespaced CRDs) it would be a no-op, so it is omitted entirely.
 * The whole header is suppressed on the connecting / error screens, where
 * neither control has anything to act on.
 */
@Composable
internal fun SessionContentHeader(
    screen: Screen,
    clusterContext: String,
    selectedNamespace: String,
    namespaces: List<String>,
    onNamespaceChange: (String) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
) {
    if (!screen.showsContentHeader()) return

    Box(modifier = Modifier.fillMaxWidth().background(KdSurface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (clusterContext.isNotBlank()) {
                Text(
                    text = clusterContext,
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            if (screen.showsNamespaceSelector()) {
                CompactNamespaceSelector(selectedNamespace, namespaces, onNamespaceChange)
            }

            CompactSearchField(searchQuery, onSearchChange)
        }
        HorizontalDivider(
            color = KdBorder,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart),
        )
    }
}

/** False only for the transient connecting/error screens. */
private fun Screen.showsContentHeader(): Boolean = when (this) {
    is Screen.Main.Connecting,
    is Screen.Main.ConnectionError,
    -> false

    else -> true
}

/**
 * Whether the namespace filter is meaningful for this screen. True for
 * namespaced resource lists; false for cluster-scoped views. CRDs carry their
 * own scope flag.
 */
private fun Screen.showsNamespaceSelector(): Boolean = when (this) {
    is Screen.Main.Pods,
    is Screen.Main.Deployments,
    is Screen.Main.Events,
    is Screen.Main.StatefulSets,
    is Screen.Main.DaemonSets,
    is Screen.Main.ReplicaSets,
    is Screen.Main.Jobs,
    is Screen.Main.CronJobs,
    is Screen.Main.ConfigMaps,
    is Screen.Main.Secrets,
    is Screen.Main.Services,
    is Screen.Main.Ingresses,
    is Screen.Main.Endpoints,
    is Screen.Main.NetworkPolicies,
    is Screen.Main.ServiceAccounts,
    is Screen.Main.Roles,
    is Screen.Main.RoleBindings,
    is Screen.Main.HorizontalPodAutoscalers,
    is Screen.Main.PodDisruptionBudgets,
    is Screen.Main.PersistentVolumeClaims,
    is Screen.Main.ClusterTopology,
    -> true

    is Screen.Main.CustomResource -> namespaced

    else -> false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSearchField(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = KdPrimary,
        unfocusedBorderColor = KdBorder,
        cursorColor = KdPrimary,
        focusedContainerColor = KdSurfaceVariant,
        unfocusedContainerColor = KdSurfaceVariant,
    )
    BasicTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = KdTextPrimary),
        cursorBrush = SolidColor(KdPrimary),
        interactionSource = interactionSource,
        modifier = Modifier.width(200.dp).height(if (sessionHeaderIsMacOS) 30.dp else 32.dp),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = searchQuery,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = {
                    Text("Search...", style = MaterialTheme.typography.bodySmall, color = KdTextPlaceholder)
                },
                leadingIcon = {
                    Icon(painterResource(Res.drawable.search_filled), null, Modifier.size(14.dp), tint = KdTextPlaceholder)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(14.dp)) {
                            Icon(painterResource(Res.drawable.close_filled), "Clear search", Modifier.size(12.dp), tint = KdTextSecondary)
                        }
                    }
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = RoundedCornerShape(4.dp),
                    )
                },
            )
        },
    )
}

@Composable
private fun CompactNamespaceSelector(
    selectedNamespace: String,
    namespaces: List<String>,
    onNamespaceChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val buttonHeight = 28.dp

    Box {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.height(buttonHeight),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
            border = ButtonDefaults.outlinedButtonBorder(true).copy(
                brush = SolidColor(KdBorder),
            ),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(painterResource(Res.drawable.folder_special_filled), null, Modifier.size(12.dp), tint = KdTextSecondary)
            Spacer(Modifier.width(4.dp))
            Text(selectedNamespace, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(2.dp))
            Icon(painterResource(Res.drawable.expand_more_filled), "Show namespaces", Modifier.size(12.dp))
        }

        if (expanded) {
            // Deterministic height: wrap content but cap at 320dp so the popup
            // never grows unbounded (a custom VerticalScrollbar's fillMaxHeight
            // would otherwise resolve to an infinite constraint and crash).
            // +1 row for the "All Namespaces" entry; +13 for divider + padding.
            val rowHeight = 38.dp
            val menuHeight = (rowHeight * (namespaces.size + 1) + 13.dp)
                .coerceAtMost(320.dp)
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { buttonHeight.roundToPx() + 2 }),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 320.dp)
                        .height(menuHeight)
                        .background(KdSurface, RoundedCornerShape(4.dp))
                        .border(1.dp, KdBorder, RoundedCornerShape(4.dp)),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(vertical = 4.dp),
                    ) {
                        NamespaceMenuRow(
                            label = "All Namespaces (${namespaces.size})",
                            selected = selectedNamespace == "All Namespaces",
                            onClick = {
                                onNamespaceChange("All Namespaces")
                                expanded = false
                            },
                        )
                        HorizontalDivider(color = KdBorder)
                        namespaces.forEach { ns ->
                            NamespaceMenuRow(
                                label = ns,
                                selected = ns == selectedNamespace,
                                onClick = {
                                    onNamespaceChange(ns)
                                    expanded = false
                                },
                            )
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NamespaceMenuRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(16.dp)) {
            if (selected) {
                Icon(
                    painterResource(Res.drawable.check_filled),
                    null,
                    tint = KdPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) KdPrimary else KdTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
