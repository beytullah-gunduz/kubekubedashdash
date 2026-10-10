package com.kubekubedashdash.ui.modals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdOutlineWidth
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_filled
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.resources.dns_filled
import com.kubekubedashdash.resources.hub
import com.kubekubedashdash.resources.open_in_new_filled
import com.kubekubedashdash.resources.science_filled
import com.kubekubedashdash.resources.star_filled
import com.kubekubedashdash.resources.star_outline
import com.kubekubedashdash.resources.tab_filled
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.ui.SidebarSearchBox
import com.kubekubedashdash.ui.components.BusyScanner
import com.kubekubedashdash.ui.components.LoadingCaption
import com.kubekubedashdash.ui.crt.CrtGhost
import com.kubekubedashdash.ui.crt.crtCardReveal
import com.kubekubedashdash.util.ContextBinding
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.EksClusterDiscoverer
import com.kubekubedashdash.util.GkeClusterDiscoverer
import com.kubekubedashdash.util.KubeconfigReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

private val EksOrange = Color(0xFFFF9900)
private val GkeBlue = Color(0xFF4285F4)
private val MockTeal = Color(0xFF00BFA5)

internal data class ParsedContext(
    val rawName: String,
    val isEks: Boolean,
    val isMock: Boolean = false,
    val clusterName: String,
    val awsAccount: String? = null,
    val awsRegion: String? = null,
    val awsProfile: String? = null,
    val isGke: Boolean = false,
    val gcpProject: String? = null,
    val gcpLocation: String? = null,
)

internal fun parseContext(ctx: String, awsProfile: String?): ParsedContext {
    if (DemoContext.isMockContext(ctx)) {
        // Bare prefix (picker template) → "Demo Cluster"; live "$prefix #N" → "Demo Cluster #N".
        val suffix = ctx.removePrefix(DemoContext.MOCK_CONTEXT_NAME).trim()
        val displayName = if (suffix.isEmpty()) "Demo Cluster" else "Demo Cluster $suffix"
        return ParsedContext(
            rawName = ctx,
            isEks = false,
            isMock = true,
            clusterName = displayName,
        )
    }
    val gkeParsed = GkeClusterDiscoverer.parseGkeContext(ctx)
    if (gkeParsed != null) {
        val (project, location, clusterName) = gkeParsed
        return ParsedContext(
            rawName = ctx,
            isEks = false,
            isGke = true,
            clusterName = clusterName,
            gcpProject = project,
            gcpLocation = location,
        )
    }
    val eks = EksClusterDiscoverer.parseEksContext(ctx)
    return if (eks != null) {
        ParsedContext(
            rawName = ctx,
            isEks = true,
            clusterName = eks.clusterName,
            awsRegion = eks.region,
            awsAccount = eks.accountId,
            awsProfile = awsProfile,
        )
    } else {
        ParsedContext(
            rawName = ctx,
            isEks = false,
            clusterName = ctx,
            awsProfile = awsProfile,
        )
    }
}

/** Test tags for the picker's search field, sections, rows and stars. */
internal object ClusterPickerTags {
    const val SEARCH = "cluster-picker-search"
    const val FAVOURITES_HEADER = "cluster-picker-favourites-header"
    const val NO_MATCH = "cluster-picker-no-match"

    fun row(ctx: String) = "cluster-picker-row/$ctx"

    fun star(ctx: String) = "cluster-picker-star/$ctx"
}

/** One entry of the picker's list, in display order. Keys are unique across kinds. */
internal sealed interface PickerItem {
    val key: String

    data class FavouritesHeader(val count: Int) : PickerItem {
        override val key: String get() = "favourites-header"
    }

    data object FavouritesDivider : PickerItem {
        override val key: String get() = "favourites-divider"
    }

    data class AwsAccountHeader(val account: String, val count: Int) : PickerItem {
        override val key: String get() = "account-header/$account"
    }

    data class GcpProjectHeader(val project: String, val count: Int) : PickerItem {
        override val key: String get() = "project-header/$project"
    }

    data class Row(val parsed: ParsedContext) : PickerItem {
        override val key: String get() = "row/${parsed.rawName}"
    }
}

/**
 * True when every whitespace-separated token of [query] appears, ignoring case, in one of
 * the row's fields: context name, display name, AWS account/region/profile, GCP
 * project/location. A blank query matches every row.
 */
internal fun matchesClusterQuery(parsed: ParsedContext, query: String): Boolean {
    // A non-breaking space (Option+Space on a Mac) separates words like a space.
    val tokens = query.trim().lowercase().split(Regex("[\\s\\u00A0]+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    val fields = listOfNotNull(
        parsed.rawName,
        parsed.clusterName,
        parsed.awsAccount,
        parsed.awsRegion,
        parsed.awsProfile,
        parsed.gcpProject,
        parsed.gcpLocation,
    ).map { it.lowercase() }
    return tokens.all { token -> fields.any { token in it } }
}

/**
 * The picker's list in display order. Favourites come first, in [favouriteKeys] order
 * ([DemoContext.preferenceKey] values; a key with no context is skipped), and are left out
 * of the groups below, so a starred cluster is listed once. Then, as before: the demo
 * cluster, plain contexts, EKS by account and GKE by project, with a group header only when
 * more than one account (project) is listed. [query] filters every section, and a section
 * left without rows is dropped with its header.
 */
internal fun pickerItems(
    parsed: List<ParsedContext>,
    favouriteKeys: List<String>,
    query: String,
): List<PickerItem> {
    val matching = parsed.filter { matchesClusterQuery(it, query) }
    val keys = favouriteKeys.distinct()
    val favourites = keys.flatMap { key -> matching.filter { DemoContext.preferenceKey(it.rawName) == key } }
    val keySet = keys.toSet()
    val rest = matching.filter { DemoContext.preferenceKey(it.rawName) !in keySet }

    val (mock, rest1) = rest.partition { it.isMock }
    val (eks, rest2) = rest1.partition { it.isEks }
    val (gke, other) = rest2.partition { it.isGke }
    val eksByAccount = eks.groupBy { it.awsAccount.orEmpty() }
    val gkeByProject = gke.groupBy { it.gcpProject.orEmpty() }

    val items = mutableListOf<PickerItem>()
    if (favourites.isNotEmpty()) {
        items += PickerItem.FavouritesHeader(favourites.size)
        favourites.forEach { items += PickerItem.Row(it) }
        if (rest.isNotEmpty()) items += PickerItem.FavouritesDivider
    }
    mock.forEach { items += PickerItem.Row(it) }
    other.forEach { items += PickerItem.Row(it) }
    eksByAccount.keys.sorted().forEach { account ->
        val list = eksByAccount.getValue(account)
        if (eksByAccount.size > 1) items += PickerItem.AwsAccountHeader(account, list.size)
        list.forEach { items += PickerItem.Row(it) }
    }
    gkeByProject.keys.sorted().forEach { project ->
        val list = gkeByProject.getValue(project)
        if (gkeByProject.size > 1) items += PickerItem.GcpProjectHeader(project, list.size)
        list.forEach { items += PickerItem.Row(it) }
    }
    return items
}

/**
 * [favourites] are the starred [DemoContext.preferenceKey] values, live: they fill the
 * stars. The list's order reads them once per opening, re-reading when [favouritesReady]
 * turns true (the stored ones have loaded), so a star never moves a row under the pointer.
 * [onToggleFavourite] receives the row's context; null hides the stars.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClusterSelectorModal(
    contexts: List<String>,
    selectedContext: String,
    onOpenCluster: (String, OpenTarget) -> Unit,
    onDismiss: () -> Unit,
    onDiscoverEks: () -> Unit = {},
    onDiscoverGke: () -> Unit = {},
    onOpenAllClusters: (() -> Unit)? = null,
    dismissable: Boolean = true,
    canAddTab: Boolean = false,
    defaultTarget: OpenTarget = OpenTarget.CURRENT_VIEW,
    crtGhost: CrtGhost? = null,
    favourites: List<String> = emptyList(),
    favouritesReady: Boolean = true,
    onToggleFavourite: ((String) -> Unit)? = null,
) {
    var bindings by remember { mutableStateOf(emptyMap<String, ContextBinding>()) }
    LaunchedEffect(contexts) {
        bindings = withContext(Dispatchers.IO) {
            KubeconfigReader.Default.contextBindings().associateBy { it.name }
        }
    }
    var query by remember { mutableStateOf("") }
    // Index into `rows`; -1 = nothing highlighted.
    var highlighted by remember { mutableStateOf(-1) }
    // A copy: the caller may pass a mutable list, and the order must not follow it.
    val layoutFavourites = remember(favouritesReady) { favourites.toList() }
    val favouriteKeySet = favourites.toSet()
    val parsedContexts = remember(contexts, bindings) {
        contexts.map { ctx -> parseContext(ctx, bindings[ctx]?.awsProfile) }
    }
    val items = remember(parsedContexts, layoutFavourites, query) {
        pickerItems(parsedContexts, layoutFavourites, query)
    }
    val rows = remember(items) { items.filterIsInstance<PickerItem.Row>() }
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    // The arrows and Enter drive the highlight only from the search field: a row or
    // button reached with Tab keeps its own Enter.
    var searchFocused by remember { mutableStateOf(false) }
    // The list area's height with no query: a filtered list keeps it, so the card and the
    // field in it do not shrink towards the centre on every keystroke.
    val density = LocalDensity.current
    var unfilteredListHeight by remember { mutableStateOf(0.dp) }
    val awsCliAvailable = remember { EksClusterDiscoverer.isAwsCliAvailable() }
    val gcloudCliAvailable = remember { GkeClusterDiscoverer.isGcloudAvailable() }
    val modalFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { searchFocus.requestFocus() }
            .onFailure { runCatching { modalFocus.requestFocus() } }
    }
    LaunchedEffect(highlighted, items) {
        val target = rows.getOrNull(highlighted) ?: return@LaunchedEffect
        // The first row scrolls the list to its top, so the Favourites header above it shows too.
        val index = if (highlighted == 0) 0 else items.indexOfFirst { it.key == target.key }
        if (index < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val shown = info.visibleItemsInfo.firstOrNull { it.index == index }
        when {
            shown == null -> listState.animateScrollToItem(index)

            shown.offset < info.viewportStartOffset ->
                listState.animateScrollBy((shown.offset - info.viewportStartOffset).toFloat())

            shown.offset + shown.size > info.viewportEndOffset ->
                listState.animateScrollBy((shown.offset + shown.size - info.viewportEndOffset).toFloat())
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(modalFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    // A query is cleared first; the next Escape closes the picker.
                    Key.Escape -> when {
                        query.isNotEmpty() -> {
                            query = ""
                            highlighted = -1
                            true
                        }

                        dismissable -> {
                            onDismiss()
                            true
                        }

                        else -> false
                    }

                    Key.DirectionDown -> {
                        if (!searchFocused) return@onPreviewKeyEvent false
                        if (rows.isNotEmpty()) highlighted = (highlighted + 1).coerceAtMost(rows.lastIndex)
                        true
                    }

                    Key.DirectionUp -> {
                        if (!searchFocused) return@onPreviewKeyEvent false
                        if (rows.isNotEmpty()) highlighted = (highlighted - 1).coerceAtLeast(0)
                        true
                    }

                    Key.Enter, Key.NumPadEnter -> {
                        if (!searchFocused) return@onPreviewKeyEvent false
                        rows.getOrNull(highlighted)?.let { row ->
                            onOpenCluster(row.parsed.rawName, defaultTarget)
                            onDismiss()
                        }
                        true
                    }

                    else -> false
                }
            }
            .background(Color.Black.copy(alpha = 0.45f))
            .then(
                if (dismissable) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 700.dp)
                // Swallows clicks without taking focus, so a click on the card's
                // background leaves the search field focused.
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .crtCardReveal(crtGhost),
            shape = 12.dp.kdCorner,
            color = KdSurface,
            border = BorderStroke(kdOutlineWidth, KdBorder),
            shadowElevation = 16.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(8.dp.kdCorner)
                            .background(KdPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(Res.drawable.cloud_filled),
                            contentDescription = null,
                            tint = KdPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Select Cluster",
                            style = MaterialTheme.typography.titleMedium,
                            color = KdTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                contexts.isEmpty() -> "Loading…"
                                query.isNotBlank() -> "${rows.size} of ${contexts.size} context${if (contexts.size != 1) "s" else ""}"
                                else -> "${contexts.size} context${if (contexts.size != 1) "s" else ""} available"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                        )
                    }
                    if (dismissable) {
                        Icon(
                            painterResource(Res.drawable.close_filled),
                            contentDescription = "Close",
                            tint = KdTextSecondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(4.dp.kdCorner)
                                .clickable(onClick = onDismiss),
                        )
                    }
                }

                SidebarSearchBox(
                    query = query,
                    onChange = {
                        query = it
                        highlighted = if (it.isBlank()) -1 else 0
                    },
                    placeholder = "Filter by name, account, region or project",
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .focusRequester(searchFocus)
                        .onFocusChanged { searchFocused = it.isFocused }
                        .testTag(ClusterPickerTags.SEARCH),
                    height = 32.dp,
                    verticalPadding = 0.dp,
                )

                HorizontalDivider(color = KdBorder, thickness = 1.dp)

                if (contexts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BusyScanner(ringSize = 28.dp, color = KdPrimary, strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            LoadingCaption(
                                "Loading clusters…",
                                style = MaterialTheme.typography.bodySmall,
                                color = KdTextSecondary,
                            )
                        }
                    }
                } else if (rows.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .heightIn(min = unfilteredListHeight)
                            .padding(horizontal = 20.dp, vertical = 24.dp)
                            .testTag(ClusterPickerTags.NO_MATCH),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No clusters match \"${query.trim()}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = KdTextSecondary,
                        )
                    }
                } else {
                    // Weighted, so the footer below is measured first and the list is
                    // what shrinks in a short window.
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .heightIn(max = 700.dp)
                            .then(if (query.isNotBlank()) Modifier.heightIn(min = unfilteredListHeight) else Modifier)
                            .onSizeChanged { if (query.isBlank()) unfilteredListHeight = with(density) { it.height.toDp() } },
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        ) {
                            items.forEach { entry ->
                                item(key = entry.key) {
                                    when (entry) {
                                        is PickerItem.FavouritesHeader -> FavouritesSectionHeader(
                                            count = entry.count,
                                            modifier = Modifier.animateItem(),
                                        )

                                        PickerItem.FavouritesDivider -> HorizontalDivider(
                                            modifier = Modifier
                                                .animateItem()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            color = KdBorder,
                                            thickness = 1.dp,
                                        )

                                        is PickerItem.AwsAccountHeader -> AwsAccountSectionHeader(
                                            account = entry.account.ifBlank { "(unknown account)" },
                                            count = entry.count,
                                            modifier = Modifier.animateItem(),
                                        )

                                        is PickerItem.GcpProjectHeader -> GcpProjectSectionHeader(
                                            project = entry.project.ifBlank { "(unknown project)" },
                                            count = entry.count,
                                            modifier = Modifier.animateItem(),
                                        )

                                        is PickerItem.Row -> {
                                            val ctx = entry.parsed.rawName
                                            ClusterRow(
                                                ctx = ctx,
                                                parsed = entry.parsed,
                                                // Light up the "Demo Cluster" row whenever any mock
                                                // instance ("…#N") is the active session — otherwise the
                                                // bare-prefix entry would never match a live label.
                                                isSelected = ctx == selectedContext ||
                                                    (entry.parsed.isMock && DemoContext.isMockContext(selectedContext)),
                                                isHighlighted = rows.getOrNull(highlighted)?.key == entry.key,
                                                isFavourite = DemoContext.preferenceKey(ctx) in favouriteKeySet,
                                                // A click focuses the star (desktop clickables take focus on
                                                // press); hand it back so typing, the arrows and Enter go on working.
                                                onToggleFavourite = onToggleFavourite?.let { toggle ->
                                                    {
                                                        toggle(ctx)
                                                        runCatching { searchFocus.requestFocus() }
                                                    }
                                                },
                                                canAddTab = canAddTab,
                                                defaultTarget = defaultTarget,
                                                onOpenCluster = onOpenCluster,
                                                onDismiss = onDismiss,
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // In a box of its own so the list, not the scrollbar, sets the height:
                        // a fillMaxHeight scrollbar here made the list area as tall as allowed.
                        Box(Modifier.matchParentSize()) {
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(listState),
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            )
                        }
                    }
                }

                HorizontalDivider(color = KdBorder, thickness = 1.dp)

                Column {
                    if (onOpenAllClusters != null) {
                        var fleetFooterHovered by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenAllClusters() }
                                .onPointerEvent(PointerEventType.Enter) { fleetFooterHovered = true }
                                .onPointerEvent(PointerEventType.Exit) { fleetFooterHovered = false }
                                .background(if (fleetFooterHovered) KdHover else Color.Transparent)
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(6.dp.kdCorner)
                                    .background(KdPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(Res.drawable.hub),
                                    contentDescription = null,
                                    tint = KdPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "All Clusters view",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = KdTextPrimary,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Compare the clusters open in your windows on one page",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KdTextSecondary,
                                )
                            }
                        }

                        HorizontalDivider(color = KdBorder, thickness = 1.dp)
                    }

                    var footerHovered by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (awsCliAvailable) {
                                    Modifier
                                        .clickable { onDiscoverEks() }
                                        .onPointerEvent(PointerEventType.Enter) { footerHovered = true }
                                        .onPointerEvent(PointerEventType.Exit) { footerHovered = false }
                                } else {
                                    Modifier
                                },
                            )
                            .background(if (footerHovered) KdHover else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(6.dp.kdCorner)
                                .background(
                                    if (awsCliAvailable) {
                                        EksOrange.copy(alpha = 0.15f)
                                    } else {
                                        KdSurfaceVariant
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(Res.drawable.cloud_filled),
                                contentDescription = null,
                                tint = if (awsCliAvailable) EksOrange else KdTextSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Discover EKS clusters",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (awsCliAvailable) KdTextPrimary else KdTextSecondary,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (awsCliAvailable) {
                                    "Add EKS clusters from your AWS account to kubeconfig"
                                } else {
                                    "Requires AWS CLI on PATH"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = KdTextSecondary,
                            )
                        }
                    }

                    HorizontalDivider(color = KdBorder, thickness = 1.dp)

                    var gkeFooterHovered by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (gcloudCliAvailable) {
                                    Modifier
                                        .clickable { onDiscoverGke() }
                                        .onPointerEvent(PointerEventType.Enter) { gkeFooterHovered = true }
                                        .onPointerEvent(PointerEventType.Exit) { gkeFooterHovered = false }
                                } else {
                                    Modifier
                                },
                            )
                            .background(if (gkeFooterHovered) KdHover else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(6.dp.kdCorner)
                                .background(
                                    if (gcloudCliAvailable) {
                                        GkeBlue.copy(alpha = 0.15f)
                                    } else {
                                        KdSurfaceVariant
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(Res.drawable.cloud_filled),
                                contentDescription = null,
                                tint = if (gcloudCliAvailable) GkeBlue else KdTextSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Discover GKE clusters",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (gcloudCliAvailable) KdTextPrimary else KdTextSecondary,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (gcloudCliAvailable) {
                                    "Add GKE clusters from your Google Cloud projects to kubeconfig"
                                } else {
                                    "Requires Google Cloud SDK on PATH"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = KdTextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClusterActionTooltip(text: String) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.ClusterRow(
    ctx: String,
    parsed: ParsedContext,
    isSelected: Boolean,
    isHighlighted: Boolean,
    isFavourite: Boolean,
    onToggleFavourite: (() -> Unit)?,
    canAddTab: Boolean,
    defaultTarget: OpenTarget,
    onOpenCluster: (String, OpenTarget) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        isSelected -> KdSelected
        hovered || isHighlighted -> KdHover
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .testTag(ClusterPickerTags.row(ctx))
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(8.dp.kdCorner)
            .background(bg)
            // The keyboard highlight is a ring, like kdFocusRing, so it shows on the
            // selected row too (whose background already says "selected") and stays
            // distinct from a mouse hover.
            .then(if (isHighlighted) Modifier.border(2.dp, KdPrimary, 8.dp.kdCorner) else Modifier)
            .clickable {
                onOpenCluster(ctx, defaultTarget)
                onDismiss()
            }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parsed.isMock) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(6.dp.kdCorner)
                    .background(MockTeal),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.science_filled),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else if (parsed.isEks) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(6.dp.kdCorner)
                    .background(EksOrange),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "EKS",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
        } else if (parsed.isGke) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(6.dp.kdCorner)
                    .background(GkeBlue),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "GKE",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(6.dp.kdCorner)
                    .background(
                        if (isSelected) {
                            KdPrimary.copy(alpha = 0.15f)
                        } else {
                            KdSurfaceVariant
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.dns_filled),
                    contentDescription = null,
                    tint = if (isSelected) KdPrimary else KdTextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                parsed.clusterName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) KdPrimary else KdTextPrimary,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (parsed.isMock) {
                Text(
                    "In-memory mock cluster with sample data",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (parsed.isEks) {
                val base = "${parsed.awsAccount} · ${parsed.awsRegion}"
                val subtitle = parsed.awsProfile?.let { "$base · profile: $it" } ?: base
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (parsed.awsProfile == null) {
                    Text(
                        "no profile bound — uses default credentials",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (parsed.isGke) {
                Text(
                    "${parsed.gcpProject} · ${parsed.gcpLocation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onToggleFavourite != null) {
            Spacer(Modifier.width(8.dp))
            TooltipArea(
                tooltip = { ClusterActionTooltip(if (isFavourite) "Remove from favourites" else "Add to favourites") },
                tooltipPlacement = TooltipPlacement.CursorPoint(
                    offset = DpOffset(0.dp, 16.dp),
                ),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(6.dp.kdCorner)
                        .background(if (hovered) KdSurfaceVariant else Color.Transparent)
                        .clickable(onClick = onToggleFavourite)
                        .testTag(ClusterPickerTags.star(ctx)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Same convention as a pinned table row: always present, faint until hovered.
                    Icon(
                        painterResource(if (isFavourite) Res.drawable.star_filled else Res.drawable.star_outline),
                        contentDescription = if (isFavourite) {
                            "Remove $ctx from favourites"
                        } else {
                            "Add $ctx to favourites"
                        },
                        tint = if (isFavourite) KdPrimary else KdTextSecondary.copy(alpha = if (hovered) 0.6f else 0.25f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (canAddTab) {
            Spacer(Modifier.width(8.dp))
            // Hide the per-row tab button when row-click already
            // does NEW_TAB (i.e. opened from the tab strip's
            // + button); it would just duplicate the action.
            if (defaultTarget != OpenTarget.NEW_TAB) {
                TooltipArea(
                    tooltip = { ClusterActionTooltip("Open in a new tab") },
                    tooltipPlacement = TooltipPlacement.CursorPoint(
                        offset = DpOffset(0.dp, 16.dp),
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(6.dp.kdCorner)
                            .background(if (hovered) KdSurfaceVariant else Color.Transparent)
                            .clickable {
                                onOpenCluster(ctx, OpenTarget.NEW_TAB)
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(Res.drawable.tab_filled),
                            contentDescription = "Open $ctx in new tab",
                            tint = KdTextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            TooltipArea(
                tooltip = { ClusterActionTooltip("Open in a new window") },
                tooltipPlacement = TooltipPlacement.CursorPoint(
                    offset = DpOffset(0.dp, 16.dp),
                ),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(6.dp.kdCorner)
                        .background(if (hovered) KdSurfaceVariant else Color.Transparent)
                        .clickable {
                            onOpenCluster(ctx, OpenTarget.NEW_WINDOW)
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(Res.drawable.open_in_new_filled),
                        contentDescription = "Open $ctx in new window",
                        tint = KdTextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        // Always reserve the trailing slot so the tab/window action
        // icons stay vertically aligned across selected and unselected rows.
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.size(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(
                    painterResource(Res.drawable.check_filled),
                    contentDescription = null,
                    tint = KdPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun FavouritesSectionHeader(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ClusterPickerTags.FAVOURITES_HEADER)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(Res.drawable.star_filled),
            contentDescription = null,
            tint = KdPrimary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Favourites",
            color = KdTextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count cluster${if (count != 1) "s" else ""}",
            color = KdTextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun AwsAccountSectionHeader(account: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(4.dp.kdCorner)
                .background(EksOrange.copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                "AWS",
                color = EksOrange,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            account,
            color = KdTextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count cluster${if (count != 1) "s" else ""}",
            color = KdTextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun GcpProjectSectionHeader(project: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(4.dp.kdCorner)
                .background(GkeBlue.copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                "GCP",
                color = GkeBlue,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            project,
            color = KdTextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count cluster${if (count != 1) "s" else ""}",
            color = KdTextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
