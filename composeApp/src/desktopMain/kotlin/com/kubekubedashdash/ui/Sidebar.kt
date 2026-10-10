package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kubekubedashdash.KdAccent
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdOnError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSidebarBg
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.data.repository.CrdPreferenceRepository
import com.kubekubedashdash.data.repository.NavPreferenceRepository
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdCorners
import com.kubekubedashdash.kdOutlineWidth
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.orCompact
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.chevron_right_filled
import com.kubekubedashdash.resources.expand_more_filled
import com.kubekubedashdash.resources.more_horiz_filled
import com.kubekubedashdash.resources.search_filled
import com.kubekubedashdash.retroChrome
import com.kubekubedashdash.ui.components.SeverityDot
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterHealthSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.HealthLevel
import com.kubekubedashdash.util.DemoContext
import kotlinx.coroutines.flow.mapNotNull
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
    // The 56 dp rail has no box to show a query in; clear it on collapse so
    // re-expanding does not come back silently filtered.
    LaunchedEffect(collapsed) { if (collapsed) searchQuery = "" }

    // Collected here (not just inside CrdSection) so the "No matches" message
    // can account for CRD hits without CustomResourcesSection reaching back
    // out to Sidebar. See CrdSection below for the CRD half's own rendering.
    val client = LocalReactiveKubeClient.current
    val crdsState by client.crds.collectAsState()

    // Trailing signal counts (pods failing / nodes not ready / warning
    // events) — the same three the cluster health banner surfaces, keyed by
    // NavKind.key so NavKindItem can look one up per row for free.
    val counts = remember(clusterHealth) { sidebarCounts(clusterHealth) }

    // The cluster context, read live and only once connected — deliberately
    // NOT remembered. Before connect, getCurrentContext() returns the
    // kubeconfig's current-context and caches it, while the demo cluster
    // mints its real "… (mock)#N" label only after connect; a remembered
    // value here would write favourites under one key and read them under
    // another. Blank while disconnected, and nothing below renders then.
    val connected = LocalIsConnected.current
    val favouritesContext = if (connected) DemoContext.preferenceKey(client.getCurrentContext()) else ""
    val favouritesByContext by NavPreferenceRepository.favouritesByContext.collectAsState()
    val hiddenByContext by CrdPreferenceRepository.hiddenByContext.collectAsState()
    val favouriteKeys = favouritesByContext[favouritesContext].orEmpty()
    val hiddenCrdKeys = hiddenByContext[favouritesContext].orEmpty()
    val favouriteKeySet = remember(favouriteKeys) { favouriteKeys.toSet() }

    // Last-known CRD list, held across Loading and Error: the CRD informer is
    // rebuilt on every connection version and re-emits Loading each time, so
    // reading the instantaneous state would blink every CRD favourite out of
    // the rail on each reconnect. A genuine deletion still drops the row on
    // the next Success. Hidden CRDs are excluded here as on every other CRD
    // surface — a hidden kind must not live on at the top of the rail.
    val knownCrds by remember(client) {
        client.crds.mapNotNull { (it as? ResourceState.Success)?.data }
    }.collectAsState(null)
    val crdsForShortcuts = knownCrds?.filterNot { it.key in hiddenCrdKeys }
    val favouriteShortcuts = remember(favouriteKeys, crdsForShortcuts) {
        resolveNavFavourites(favouriteKeys, NavKinds, crdsForShortcuts)
    }
    // Null while disconnected: no context to write under, so no menu item
    // that would silently do nothing.
    val onToggleKindFavourite: ((String) -> Unit)? = if (favouritesContext.isBlank()) {
        null
    } else {
        { key -> NavPreferenceRepository.toggleFavourite(favouritesContext, key) }
    }
    val onToggleCrdFavourite: ((CrdInfo) -> Unit)? = if (favouritesContext.isBlank()) {
        null
    } else {
        { crd -> NavPreferenceRepository.toggleFavourite(favouritesContext, crd.key) }
    }

    val crdMatchCount = remember(crdsState, hiddenCrdKeys, searchQuery) {
        if (searchQuery.isBlank()) {
            0
        } else {
            val crds = (crdsState as? ResourceState.Success)?.data.orEmpty()
            crds.count { it.key !in hiddenCrdKeys && matchesCrdSearch(it, searchQuery) }
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
                // Same band as the content header beside it.
                height = ContentHeaderControlHeight,
                verticalPadding = ContentHeaderVerticalPadding,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp.orCompact(2.dp)),
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
                    onToggleFavourite = onToggleCrdFavourite ?: {},
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
                // Favourites sit above the Cluster block. They duplicate rows
                // from the sections below rather than moving them — a
                // favourited kind keeps its place in its own section. Hidden
                // entirely while disconnected: resolution depends on a real
                // cluster context. There is deliberately no Recent section
                // here: a most-recent-first list above the catalogue changed
                // height on most navigations and moved every row below it.
                // The command palette keeps its own Recent group.
                if (favouritesContext.isNotBlank() && favouriteShortcuts.isNotEmpty()) {
                    SidebarSection("Favourites", collapsed, separated = false) {
                        favouriteShortcuts.forEach { shortcut ->
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
                    // The Cluster block below has no header — it was the top of
                    // the rail before Favourites existed — so with a section
                    // above it, its first row reads as that section's tail. One
                    // rule marks "yours" from "the catalogue".
                    SidebarTierDivider(collapsed)
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
                    if (collapsed) {
                        MoreFlyoutItem(currentScreen, onNavigate)
                    } else {
                        NavSections.filter { it.tier == NavTier.MORE }.forEach { section ->
                            SidebarSubLabel(section.title)
                            section.kinds.forEach { kind ->
                                NavKindItem(kind, currentScreen, collapsed, clusterHealth, counts, favouriteKeySet, onToggleKindFavourite, onNavigate)
                            }
                        }
                    }
                }

                CrdSection(
                    currentScreen,
                    onNavigate,
                    collapsed,
                    favourites = favouriteKeySet,
                    onToggleFavourite = onToggleCrdFavourite ?: {},
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
    onToggleFavourite: ((String) -> Unit)?,
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
        badgeFilled = clusterHealth?.level == HealthLevel.CRITICAL,
        count = counts[kind.key],
        onCountClick = onNavigate,
        contextMenu = onToggleFavourite?.let { toggle ->
            { dismiss -> FavouriteMenuItem(kind.key, favourites, { toggle(kind.key) }, dismiss) }
        },
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

// Renders one row of the Favourites section — a duplicate of a catalogue or
// CRD row (they don't move out of their own section). Dispatches on which kind
// of key resolved, per resolveNavFavourites.
@Composable
private fun NavShortcutRow(
    shortcut: NavShortcut,
    currentScreen: Screen,
    collapsed: Boolean,
    clusterHealth: ClusterHealthSummary?,
    counts: Map<String, SidebarCount>,
    favourites: Set<String>,
    onToggleKindFavourite: ((String) -> Unit)?,
    onToggleCrdFavourite: ((CrdInfo) -> Unit)?,
    onNavigate: (Screen) -> Unit,
) {
    when (shortcut) {
        is NavShortcut.BuiltIn ->
            NavKindItem(shortcut.kind, currentScreen, collapsed, clusterHealth, counts, favourites, onToggleKindFavourite, onNavigate)

        is NavShortcut.Crd ->
            CrdShortcutRow(shortcut.crd, currentScreen, collapsed, favourites, onToggleCrdFavourite, onNavigate)
    }
}

// A CRD favourite row. CrdRow (CustomResourcesSection.kt) is
// file-private and cannot be called here, so this is a plain SidebarItem with
// only the favourite toggle in its context menu — no pin/hide, which stay
// exclusive to the main Custom Resources section.
@Composable
private fun CrdShortcutRow(
    crd: CrdInfo,
    currentScreen: Screen,
    collapsed: Boolean,
    favourites: Set<String>,
    onToggleFavourite: ((CrdInfo) -> Unit)?,
    onNavigate: (Screen) -> Unit,
) {
    val isSelected = currentScreen is Screen.Main.CustomResource &&
        currentScreen.group == crd.group && currentScreen.kind == crd.kind
    SidebarItem(
        icon = crdGroupIcon(crd.group),
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
        contextMenu = onToggleFavourite?.let { toggle ->
            { dismiss -> FavouriteMenuItem(crd.key, favourites, { toggle(crd) }, dismiss) }
        },
    )
}

// The full-width rule between Favourites and the catalogue — wider than the
// hairline after each section title, which starts after the text. Expanded,
// it spans the rounded-row width (8 dp outer margin, matching SidebarItem's).
// Collapsed, headers are gone and the icons run together, so it becomes a
// short centred stub — the activity-bar separator idiom — which collapsed
// SidebarSections also draw in place of their headers.
@Composable
private fun SidebarTierDivider(collapsed: Boolean) {
    if (collapsed) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp.orCompact(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalDivider(modifier = Modifier.width(24.dp), color = KdBorder, thickness = 1.dp)
        }
    } else {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp.orCompact(3.dp)),
            color = KdBorder,
            thickness = 1.dp,
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
fun SidebarSearchBox(
    query: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    verticalPadding: Dp = 4.dp,
) {
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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = verticalPadding)
            .height(height),
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
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = if (height < 32.dp) 2.dp else 4.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = 6.dp.kdCorner,
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
    val context = remember(client) { DemoContext.preferenceKey(client.getCurrentContext()) }
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
    // Filled = critical, hollow ring = warning, so the badge differs by shape
    // as well as colour (D18). Only read when [badge] is set.
    badgeFilled: Boolean = true,
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
                .height(32.dp.orCompact(26.dp))
                .padding(horizontal = if (collapsed) 4.dp else 8.dp)
                .clip(6.dp.kdCorner)
                .background(bg)
                .clickable(onClick = onClick)
                // The current page, for screen readers as well as the bar below.
                .semantics { this.selected = selected }
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
                        .clip(kdCorners(topEnd = 2.dp, bottomEnd = 2.dp))
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
                            // long label ellipsises before reaching it — sized
                            // for a three-digit count, which a broken cluster
                            // reaches easily.
                            Modifier.padding(end = 34.dp)
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
                    SeverityDot(
                        color = countDotColor(count.severity),
                        filled = count.severity == CountSeverity.ERROR,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
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
                                    // The target is the box, not the digit's glyph
                                    // bounds — the same widening the table's ⋮ slot has.
                                    .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                                    .clickable(enabled = onCountClick != null) { onCountClick?.invoke(count.target) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .semantics { contentDescription = count.description },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = count.value.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (count.severity == CountSeverity.ERROR) {
                                        KdOnError
                                    } else {
                                        countDotColor(count.severity)
                                    },
                                    // An ERROR count is a filled pill (D18); the pill is on the
                                    // Text, not the 24 dp clickable Box, so the hit target is unchanged.
                                    modifier = if (count.severity == CountSeverity.ERROR) {
                                        Modifier.background(KdError, 6.dp.kdCorner).padding(horizontal = 4.dp)
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                    }
                }

                badge != null -> {
                    SeverityDot(
                        color = badge,
                        filled = badgeFilled,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = if (collapsed) 4.dp else 10.dp)
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
        shape = 6.dp.kdCorner,
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

// A section header has to read as "the start of a group", not as one more
// row, so it differs from SidebarItem by layout as well as by type (Retro's
// retroChrome strips the bold and tracking): space above it but not below,
// so it binds to its own rows; title text in the rows' icon column (18 dp),
// left of their labels; a hairline after the title; the chevron at the
// trailing edge, where it no longer sits in the icon column. The title is the
// brightest text in the rail: the heading accent in Retro, like TitleBar and
// SessionContentHeader; textPrimary elsewhere, where the accent is the
// selection blue and would read as selected or as a link.
// [separated] is false only for a section that opens the rail (Favourites):
// space or a stub above it would separate it from nothing. Collapsed, the
// header gives way to the activity-bar stub SidebarTierDivider uses, and the
// content always shows, since the rail has no header to open it by: a section
// whose rows would flood the rail (More, Custom Resources) renders a
// SidebarFlyoutItem there instead of its rows.
// The expanded state is stored under [title] — renaming a title resets it.
@Composable
fun SidebarSection(
    title: String,
    collapsed: Boolean = false,
    defaultExpanded: Boolean = true,
    separated: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandedOverrides by PreferenceRepository.sidebarSectionsExpanded.collectAsState()
    val expanded = expandedOverrides[title] ?: defaultExpanded
    var hovered by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (collapsed) {
            if (separated) SidebarTierDivider(collapsed = true)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (separated) 8.dp.orCompact(4.dp) else 0.dp)
                    .padding(horizontal = 8.dp)
                    .clip(6.dp.kdCorner)
                    .background(if (hovered) KdHover else Color.Transparent)
                    .clickable(role = Role.Button) { PreferenceRepository.setSidebarSectionExpanded(title, !expanded) }
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false }
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .padding(horizontal = 10.dp, vertical = 6.dp.orCompact(3.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        .retroChrome(8.sp),
                    // Hovered, textPrimary in Retro too: G1 holds it on KdHover in every
                    // palette, where accent drops below 4.5:1 in several (PaletteGateTest G6).
                    color = if (ThemeManager.isRetro && !hovered) KdAccent else KdTextPrimary,
                )
                Spacer(Modifier.width(8.dp))
                HorizontalDivider(modifier = Modifier.weight(1f), color = KdBorder, thickness = 1.dp)
                Spacer(Modifier.width(6.dp))
                Icon(
                    painterResource(if (expanded) Res.drawable.expand_more_filled else Res.drawable.chevron_right_filled),
                    // The state is announced by stateDescription above.
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = KdTextSecondary,
                )
            }
        }

        AnimatedVisibility(visible = collapsed || expanded) {
            Column(content = content)
        }
    }
}

// The row-label column: SidebarItem's 8 dp margin + 10 dp padding + 16 dp
// icon + 10 dp gap. SidebarSubLabel starts here; SidebarSectionTest pins it
// to the rendered label so the two cannot drift apart.
private val SidebarLabelInset = 44.dp

// Second-level label inside a section: More's three source groups, and the
// Custom Resources section's Pinned and per-API-group blocks. It must read as
// neither a section header (icon column, hairline, chevron, accent in Retro)
// nor a row (icon, body text), so it starts in the row-label column, is small
// and secondary, and has less space above it than a header (8 dp, not 14).
// [chrome] is app wording: uppercased, pixel voice in Retro. Pass false for
// cluster data such as a CRD API group, which keeps its own spelling and the
// reading font — retroChrome's contract excludes data. [inset] moves it to a
// flyout menu's icon column, where it heads the entries below it.
@Composable
internal fun SidebarSubLabel(text: String, chrome: Boolean = true, inset: Dp = SidebarLabelInset) {
    Text(
        if (chrome) text.uppercase() else text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = inset,
                end = 18.dp,
                top = 8.dp.orCompact(4.dp),
                bottom = 4.dp.orCompact(2.dp),
            ),
        style = if (chrome) {
            MaterialTheme.typography.labelSmall
                .copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                .retroChrome(8.sp)
        } else {
            MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
        },
        color = KdTextSecondary,
    )
}

// The collapsed rail's stand-in for a section whose rows would flood the
// 56 dp rail — More's tier-2 kinds, or every CRD under one shared icon: one
// icon that opens those rows in a menu beside the rail. It shows as selected
// while the current screen is one of them, so the rail still says where you
// are. [content] gets a dismiss callback to close the menu on navigation.
// A Popup rather than a DropdownMenu, which can only drop below its anchor or
// flip above it: near the bottom of the rail that left the menu floating away
// from the icon. The namespace picker's popup has the same chrome.
@Composable
internal fun SidebarFlyoutItem(
    icon: DrawableResource,
    label: String,
    selected: Boolean,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SidebarItem(icon = icon, label = label, selected = selected, collapsed = true, onClick = { open = true })
        if (open) {
            Popup(
                popupPositionProvider = BesideAnchor,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
                // Escape closes it here, as the app's modals do, rather than
                // only through the window's Escape-as-back mapping.
                onKeyEvent = { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        open = false
                        true
                    } else {
                        false
                    }
                },
            ) {
                val scrollState = rememberScrollState()
                // The window caps the popup's height; a CRD list longer than
                // that scrolls, with a scrollbar sized to the menu, not the window.
                Box(
                    modifier = Modifier
                        .background(KdSurface, 6.dp.kdCorner)
                        .border(kdOutlineWidth, KdBorder, 6.dp.kdCorner),
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = 180.dp, max = 320.dp)
                            .width(IntrinsicSize.Max)
                            .verticalScroll(scrollState)
                            .padding(vertical = 4.dp),
                    ) {
                        content { open = false }
                    }
                    Box(Modifier.matchParentSize()) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scrollState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

// Places a flyout to the right of its anchor (left, right to left), its top
// level with the anchor's and slid up only as far as the window needs.
private object BesideAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right else anchorBounds.left - popupContentSize.width
        val y = anchorBounds.top.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

// One row of a SidebarFlyoutItem's menu. Rail-row height rather than the M3
// menu item's 48 dp: the CRD menu can list dozens. The current screen gets the
// rail's selected background and primary icon tint.
@Composable
internal fun SidebarFlyoutEntry(icon: DrawableResource, label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary) },
        leadingIcon = {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (selected) KdPrimary else KdTextSecondary,
            )
        },
        onClick = onClick,
        modifier = Modifier
            .height(32.dp.orCompact(26.dp))
            .background(if (selected) KdSelected else Color.Transparent)
            .semantics { this.selected = selected },
    )
}

// More in the collapsed rail: its three source groups, each under its label,
// in one menu instead of seven icons below the catalogue.
@Composable
internal fun MoreFlyoutItem(currentScreen: Screen, onNavigate: (Screen) -> Unit) {
    val sections = NavSections.filter { it.tier == NavTier.MORE }
    SidebarFlyoutItem(
        icon = Res.drawable.more_horiz_filled,
        label = "More",
        selected = sections.any { section -> section.kinds.any { it.isSelected(currentScreen) } },
    ) { dismiss ->
        sections.forEach { section ->
            SidebarSubLabel(section.title, inset = 12.dp)
            section.kinds.forEach { kind ->
                SidebarFlyoutEntry(kind.icon, kind.label, kind.isSelected(currentScreen)) {
                    dismiss()
                    onNavigate(kind.screen())
                }
            }
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
