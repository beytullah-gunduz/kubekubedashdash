package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSidebarBg
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.CrdPreferenceRepository
import com.kubekubedashdash.data.repository.NavPreferenceRepository
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.chevron_right_filled
import com.kubekubedashdash.resources.expand_more_filled
import com.kubekubedashdash.resources.extension_filled
import com.kubekubedashdash.resources.search_filled
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterHealthSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.HealthLevel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    collapsed: Boolean = false,
    // Optional cluster health snapshot for the dot on the Cluster nav item.
    // Null while informers are still syncing — treated the same as healthy
    // so the dot doesn't flash during the initial connect.
    clusterHealth: ClusterHealthSummary? = null,
) {
    // Rail-wide search: matches built-in kinds (below) and CRDs (delegated to
    // CustomResourcesSection, which owns its own CrdInfo state). The 56 dp
    // icon rail has no room for a text field, so search is inert while
    // collapsed — same as the CRD search box was before this box replaced it.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchActive = !collapsed && searchQuery.isNotBlank()

    // Collected here (not just inside CrdSection) so the "No matches" message
    // can account for CRD hits without CustomResourcesSection reaching back
    // out to Sidebar. See CrdSection below for the CRD half's own rendering.
    val client = LocalReactiveKubeClient.current
    val crdsState by client.crds.collectAsState()
    val context = remember(client) { client.getCurrentContext() }

    // Trailing signal counts (pods failing / nodes not ready / warning
    // events) — the same three the cluster health banner surfaces, keyed by
    // NavKind.key so NavKindItem can look one up per row for free.
    val counts = remember(clusterHealth) { sidebarCounts(clusterHealth) }

    // Favourites/Recent context. Deliberately NOT remembered (unlike `context`
    // above): before connect, getCurrentContext() returns the kubeconfig's
    // current-context, but the demo cluster rewrites its context to a minted
    // "… (mock)#N" label only after connect — a remembered value here would
    // freeze favourites under the wrong key. Neither section renders while
    // this is blank.
    val connected = LocalIsConnected.current
    val favouritesContext = if (connected) client.getCurrentContext() else ""
    val favouritesByContext by NavPreferenceRepository.favouritesByContext.collectAsState()
    val recentsByContext by NavPreferenceRepository.recentsByContext.collectAsState()
    val favouriteKeys = favouritesByContext[favouritesContext].orEmpty()
    val recentKeys = recentsByContext[favouritesContext].orEmpty()
    val favouriteKeySet = remember(favouriteKeys) { favouriteKeys.toSet() }
    val crdsForShortcuts = (crdsState as? ResourceState.Success)?.data
    val shortcuts = remember(favouriteKeys, recentKeys, crdsForShortcuts) {
        resolveNavShortcuts(favouriteKeys, recentKeys, NavKinds, crdsForShortcuts)
    }
    val onToggleKindFavourite: (String) -> Unit = { key ->
        NavPreferenceRepository.toggleFavourite(favouritesContext, key)
    }
    val onToggleCrdFavourite: (CrdInfo) -> Unit = { crd ->
        NavPreferenceRepository.toggleFavourite(favouritesContext, crd.key)
    }

    val crdMatchCount = remember(crdsState, context, searchQuery) {
        if (searchQuery.isBlank()) {
            0
        } else {
            val crds = (crdsState as? ResourceState.Success)?.data.orEmpty()
            val hidden = CrdPreferenceRepository.hiddenFor(context)
            crds.count { it.key !in hidden && matchesCrdSearch(it, searchQuery) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KdSidebarBg),
    ) {
        if (!collapsed) {
            SidebarSearchBox(
                query = searchQuery,
                onChange = { searchQuery = it },
                placeholder = "Search",
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            if (searchActive) {
                val builtInMatches = NavSections.flatMap { section ->
                    section.kinds.filter { kind -> matchesNavSearch(kind, section.title, searchQuery) }
                }
                builtInMatches.forEach { kind ->
                    NavKindItem(kind, currentScreen, collapsed, clusterHealth, counts, favouriteKeySet, onToggleKindFavourite, onNavigate)
                }
                CrdSection(
                    currentScreen,
                    onNavigate,
                    collapsed,
                    searchQuery,
                    favourites = favouriteKeySet,
                    onToggleFavourite = onToggleCrdFavourite,
                )
                if (builtInMatches.isEmpty() && crdMatchCount == 0) {
                    Text(
                        "No matches",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                    )
                }
            } else {
                // Favourites and Recent sit above the Cluster block. They
                // duplicate rows from the sections below rather than moving
                // them — a favourited kind keeps its place in its own
                // section. Hidden entirely while disconnected: resolution
                // depends on a real cluster context.
                if (favouritesContext.isNotBlank()) {
                    if (shortcuts.favourites.isNotEmpty()) {
                        SidebarSection("Favourites", collapsed) {
                            shortcuts.favourites.forEach { shortcut ->
                                NavShortcutRow(
                                    shortcut,
                                    currentScreen,
                                    collapsed,
                                    clusterHealth,
                                    counts,
                                    favouriteKeySet,
                                    onToggleKindFavourite,
                                    onToggleCrdFavourite,
                                    onNavigate,
                                )
                            }
                        }
                    }
                    if (shortcuts.recents.isNotEmpty()) {
                        SidebarSection("Recent", collapsed) {
                            shortcuts.recents.forEach { shortcut ->
                                NavShortcutRow(
                                    shortcut,
                                    currentScreen,
                                    collapsed,
                                    clusterHealth,
                                    counts,
                                    favouriteKeySet,
                                    onToggleKindFavourite,
                                    onToggleCrdFavourite,
                                    onNavigate,
                                )
                            }
                        }
                    }
                }

                NavSections.first().kinds.forEach { kind ->
                    NavKindItem(kind, currentScreen, collapsed, clusterHealth, counts, favouriteKeySet, onToggleKindFavourite, onNavigate)
                }

                NavSections.drop(1).filter { it.tier == NavTier.PRIMARY }.forEach { section ->
                    SidebarSection(section.title, collapsed) {
                        section.kinds.forEach { kind ->
                            NavKindItem(kind, currentScreen, collapsed, clusterHealth, counts, favouriteKeySet, onToggleKindFavourite, onNavigate)
                        }
                    }
                }

                SidebarSection("More", collapsed, defaultExpanded = false) {
                    NavSections.filter { it.tier == NavTier.MORE }.forEach { section ->
                        if (!collapsed) {
                            MoreGroupLabel(section.title)
                        }
                        section.kinds.forEach { kind ->
                            NavKindItem(kind, currentScreen, collapsed, clusterHealth, counts, favouriteKeySet, onToggleKindFavourite, onNavigate)
                        }
                    }
                }

                CrdSection(
                    currentScreen,
                    onNavigate,
                    collapsed,
                    favourites = favouriteKeySet,
                    onToggleFavourite = onToggleCrdFavourite,
                )
            }
        }
    }
}

// Renders one catalogue entry. The Cluster item is the only one that ever
// carries the health dot — special-cased by key, exactly as it was hard-coded
// before the catalogue existed.
@Composable
private fun NavKindItem(
    kind: NavKind,
    currentScreen: Screen,
    collapsed: Boolean,
    clusterHealth: ClusterHealthSummary?,
    counts: Map<String, SidebarCount>,
    favourites: Set<String>,
    onToggleFavourite: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val isCluster = kind.key == "ClusterOverview"
    SidebarItem(
        icon = kind.icon,
        label = kind.label,
        selected = kind.isSelected(currentScreen),
        collapsed = collapsed,
        badge = if (isCluster) healthBadgeColor(clusterHealth) else null,
        badgeContentDescription = if (isCluster) healthBadgeDescription(clusterHealth) else null,
        count = counts[kind.key],
        onCountClick = onNavigate,
        contextMenu = { dismiss -> FavouriteMenuItem(kind.key, favourites, { onToggleFavourite(kind.key) }, dismiss) },
        onClick = { onNavigate(kind.screen()) },
    )
}

// Shared favourite-toggle menu item — the one context-menu entry every
// catalogue row (NavKindItem) and CRD shortcut row (CrdShortcutRow) gets.
// CrdRow (CustomResourcesSection.kt) has its own copy above Pin/Hide since
// it's file-private and can't call this one.
@Composable
private fun FavouriteMenuItem(key: String, favourites: Set<String>, onToggle: () -> Unit, dismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text(if (key in favourites) "Remove from favourites" else "Add to favourites") },
        onClick = {
            onToggle()
            dismiss()
        },
    )
}

// Renders one row of the Favourites/Recent sections — a duplicate of a
// catalogue or CRD row (they don't move out of their own section). Dispatches
// on which kind of key resolved, per resolveNavShortcuts.
@Composable
private fun NavShortcutRow(
    shortcut: NavShortcut,
    currentScreen: Screen,
    collapsed: Boolean,
    clusterHealth: ClusterHealthSummary?,
    counts: Map<String, SidebarCount>,
    favourites: Set<String>,
    onToggleKindFavourite: (String) -> Unit,
    onToggleCrdFavourite: (CrdInfo) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    when (shortcut) {
        is NavShortcut.BuiltIn ->
            NavKindItem(shortcut.kind, currentScreen, collapsed, clusterHealth, counts, favourites, onToggleKindFavourite, onNavigate)

        is NavShortcut.Crd ->
            CrdShortcutRow(shortcut.crd, currentScreen, collapsed, favourites, onToggleCrdFavourite, onNavigate)
    }
}

// A CRD favourite/recent row. CrdRow (CustomResourcesSection.kt) is
// file-private and cannot be called here, so this is a plain SidebarItem with
// only the favourite toggle in its context menu — no pin/hide, which stay
// exclusive to the main Custom Resources section.
@Composable
private fun CrdShortcutRow(
    crd: CrdInfo,
    currentScreen: Screen,
    collapsed: Boolean,
    favourites: Set<String>,
    onToggleFavourite: (CrdInfo) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val isSelected = currentScreen is Screen.Main.CustomResource &&
        currentScreen.group == crd.group && currentScreen.kind == crd.kind
    SidebarItem(
        icon = Res.drawable.extension_filled,
        label = crd.kind,
        selected = isSelected,
        collapsed = collapsed,
        onClick = {
            onNavigate(
                Screen.Main.CustomResource(
                    group = crd.group,
                    version = crd.version,
                    kind = crd.kind,
                    plural = crd.plural,
                    namespaced = crd.namespaced,
                ),
            )
        },
        contextMenu = { dismiss -> FavouriteMenuItem(crd.key, favourites, { onToggleFavourite(crd) }, dismiss) },
    )
}

// Small uppercase sub-group label used inside the More section to separate
// its three source sections. Mirrors CustomResourcesSection's file-private
// MiniHeader, which Sidebar.kt cannot call. Rendered only when !collapsed:
// SidebarSection hides its own header in the 56 dp rail but still renders
// its content, so a text label here would otherwise be crammed into icons.
@Composable
private fun MoreGroupLabel(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}

// BasicTextField + DecorationBox instead of the high-level OutlinedTextField:
// M3's OutlinedTextField bakes in ~16dp vertical content padding, which at
// 32dp height clips both the typed text and the placeholder out of view.
// Generalised from CustomResourcesSection's former CrdSearchBox so one box
// can search both built-in kinds and CRDs.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarSearchBox(query: String, onChange: (String) -> Unit, placeholder: String) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = KdBorder,
        unfocusedBorderColor = KdBorder,
        focusedContainerColor = KdSurface,
        unfocusedContainerColor = KdSurface,
    )
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = KdTextPrimary),
        cursorBrush = SolidColor(KdTextPrimary),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(32.dp),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = query,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = {
                    Text(placeholder, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                },
                leadingIcon = {
                    Icon(
                        painterResource(Res.drawable.search_filled),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = KdTextSecondary,
                    )
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = RoundedCornerShape(6.dp),
                    )
                },
            )
        },
    )
}

@Composable
private fun CrdSection(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    collapsed: Boolean,
    searchQuery: String = "",
    favourites: Set<String> = emptySet(),
    onToggleFavourite: (CrdInfo) -> Unit = {},
) {
    val client = LocalReactiveKubeClient.current
    val crdsState by client.crds.collectAsState()
    val crds = (crdsState as? ResourceState.Success)?.data.orEmpty()
    if (crds.isEmpty()) return
    val context = remember(client) { client.getCurrentContext() }
    val pinnedByContext by CrdPreferenceRepository.pinnedByContext.collectAsState()
    val hiddenByContext by CrdPreferenceRepository.hiddenByContext.collectAsState()
    val pinned = pinnedByContext[context].orEmpty()
    val hidden = hiddenByContext[context].orEmpty()
    CustomResourcesSection(
        crds = crds,
        currentScreen = currentScreen,
        pinned = pinned,
        hidden = hidden,
        onNavigate = onNavigate,
        onTogglePin = { CrdPreferenceRepository.togglePinned(context, it.key) },
        onToggleHide = { CrdPreferenceRepository.toggleHidden(context, it.key) },
        collapsed = collapsed,
        searchQuery = searchQuery,
        favourites = favourites,
        onToggleFavourite = onToggleFavourite,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarItem(
    icon: DrawableResource,
    label: String,
    selected: Boolean,
    collapsed: Boolean = false,
    contextMenu: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
    // Optional small filled-circle dot at the trailing edge of the row.
    // Used today by the Cluster entry to surface health (KdWarning / KdError)
    // so issues are visible from any screen, not only the cluster overview.
    badge: Color? = null,
    // Screen-reader description for the badge dot. Read alongside the
    // item's label so e.g. "Cluster" becomes "Cluster, cluster health
    // critical" when there's a non-null badge. Required if badge is set.
    badgeContentDescription: String? = null,
    // Trailing signal count (e.g. "3 pods failing"). A second, independent
    // click target inside the row — clicking it opens count.target instead
    // of the row's own onClick. Takes priority over [badge] when both are
    // supplied; in practice only the Cluster item ever passes badge, and it
    // never carries a count.
    count: SidebarCount? = null,
    onCountClick: ((Screen.Main) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    val bg = when {
        selected -> KdSelected
        hovered -> KdHover
        else -> Color.Transparent
    }
    val hasTrailingSlot = badge != null || count != null

    val row: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = if (collapsed) 4.dp else 8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (contextMenu != null && event.buttons.isSecondaryPressed) {
                        val pos = event.changes.first().position
                        menuOffset = with(density) { DpOffset(pos.x.toDp(), pos.y.toDp()) }
                        menuExpanded = true
                    }
                },
        ) {
            if (contextMenu != null) {
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    offset = menuOffset,
                ) {
                    contextMenu { menuExpanded = false }
                }
            }
            // Selection indicator: a 3 dp primary bar hugging the leading
            // edge of the row. Visible in both collapsed and expanded modes
            // so the active page is identifiable at a glance.
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .height(18.dp)
                        .width(3.dp)
                        .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                        .background(KdPrimary),
                )
            }
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = if (collapsed) 0.dp else 10.dp)
                    .then(
                        if (!collapsed && hasTrailingSlot) {
                            // Reserve room for the trailing badge/count so a
                            // long label ellipsises before reaching it.
                            Modifier.padding(end = 26.dp)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = if (collapsed) label else null,
                    modifier = Modifier.size(16.dp),
                    tint = if (selected) KdPrimary else KdTextSecondary,
                )
                if (!collapsed) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) KdTextPrimary else KdTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                // A digit doesn't fit the 56 dp icon rail — collapsed mode
                // reuses the same dot the health badge uses, just in the
                // count's severity colour.
                count != null && collapsed -> {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(countDotColor(count.severity))
                            .semantics { contentDescription = count.description },
                    )
                }

                count != null -> {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp),
                    ) {
                        TooltipArea(
                            tooltip = { SidebarItemTooltip(count.description) },
                            tooltipPlacement = TooltipPlacement.ComponentRect(
                                anchor = Alignment.CenterEnd,
                                alignment = Alignment.CenterEnd,
                                offset = DpOffset(8.dp, 0.dp),
                            ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .clickable(enabled = onCountClick != null) { onCountClick?.invoke(count.target) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .semantics { contentDescription = count.description },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = count.value.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = countDotColor(count.severity),
                                )
                            }
                        }
                    }
                }

                badge != null -> {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = if (collapsed) 4.dp else 10.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(badge)
                            .then(
                                if (badgeContentDescription != null) {
                                    Modifier.semantics { contentDescription = badgeContentDescription }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }

    if (collapsed) {
        TooltipArea(
            tooltip = { SidebarItemTooltip(label) },
            tooltipPlacement = TooltipPlacement.ComponentRect(
                anchor = Alignment.CenterEnd,
                alignment = Alignment.CenterEnd,
                offset = DpOffset(8.dp, 0.dp),
            ),
            content = row,
        )
    } else {
        row()
    }
}

@Composable
private fun SidebarItemTooltip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = KdSurface,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
        )
    }
}

@Composable
fun SidebarSection(
    title: String,
    collapsed: Boolean = false,
    defaultExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandedOverrides by PreferenceRepository.sidebarSectionsExpanded.collectAsState()
    val expanded = expandedOverrides[title] ?: defaultExpanded

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!collapsed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { PreferenceRepository.setSidebarSectionExpanded(title, !expanded) }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (expanded) Res.drawable.expand_more_filled else Res.drawable.chevron_right_filled),
                    contentDescription = if (expanded) "Collapse section" else "Expand section",
                    modifier = Modifier.size(14.dp),
                    tint = KdTextSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
            }
        }

        AnimatedVisibility(visible = collapsed || expanded) {
            Column(content = content)
        }
    }
}

// Maps a cluster health snapshot to the dot color on the Cluster nav item.
// Returns null when the cluster is HEALTHY or the snapshot hasn't loaded yet
// — the SidebarItem omits the dot entirely in those cases.
private fun healthBadgeColor(health: ClusterHealthSummary?): Color? = when (health?.level) {
    HealthLevel.CRITICAL -> KdError
    HealthLevel.WARNING -> KdWarning
    HealthLevel.HEALTHY, null -> null
}

// Screen-reader description that pairs with the dot. Read alongside the
// item's "Cluster" label so SR users hear "Cluster, cluster health
// critical" instead of just "Cluster" when issues are present.
private fun healthBadgeDescription(health: ClusterHealthSummary?): String? = when (health?.level) {
    HealthLevel.CRITICAL -> "cluster health critical"
    HealthLevel.WARNING -> "cluster health warning"
    HealthLevel.HEALTHY, null -> null
}

// Colour for a SidebarCount's dot/digit — ERROR and WARNING mirror the same
// two tiers the health badge above uses.
private fun countDotColor(severity: CountSeverity): Color = when (severity) {
    CountSeverity.ERROR -> KdError
    CountSeverity.WARNING -> KdWarning
}
