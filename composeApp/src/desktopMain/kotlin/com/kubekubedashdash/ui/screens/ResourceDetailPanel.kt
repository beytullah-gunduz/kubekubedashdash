package com.kubekubedashdash.ui.screens

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPlaceholder
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.code_filled
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.info_filled
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.resources.search_filled
import com.kubekubedashdash.resources.security_filled
import com.kubekubedashdash.resources.swap_horiz_filled
import com.kubekubedashdash.screenshots.ScreenshotHooks
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.EMPTY_DASH
import com.kubekubedashdash.ui.components.KeyValueChipFlow
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.rememberCopyToClipboard
import com.kubekubedashdash.util.SecretYamlMasking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// ── Data class for key-value rows in the overview ───────────────────────────────

data class DetailField(
    val label: String,
    val value: String,
    val valueColor: Color? = null,
)

/**
 * One entry of a [DetailAction] dropdown menu — e.g. a container to open a
 * terminal or log view for. Rendered with the container icon.
 */
data class DetailActionMenuItem(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * An action button rendered in the [ResourceDetailPanel] header alongside Delete.
 *
 * @param label       Button tooltip / accessibility label.
 * @param icon        Icon to display (16 dp).
 * @param destructive When true the icon renders in [KdError] red; otherwise [KdTextSecondary].
 * @param enabled     Controls whether the button responds to clicks.
 * @param onClick     Invoked when the button is clicked.
 * @param menuItems   When non-empty, clicking opens a dropdown of these entries instead of invoking [onClick].
 */
data class DetailAction(
    val label: String,
    val icon: DrawableResource,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val tint: Color? = null,
    val description: String? = null,
    val onClick: () -> Unit,
    val menuItems: List<DetailActionMenuItem> = emptyList(),
)

class ExtraTab(
    val label: String,
    val icon: DrawableResource,
    val badgeCount: Int? = null,
    val isLoading: Boolean = false,
    val content: @Composable () -> Unit,
)

/**
 * A kind-specific section appended to the Overview tab, after Annotations —
 * the same shape the Node panel uses for its inline pod list. [key] exists so
 * wiring is assertable in tests; the section renders its own header.
 */
class OverviewSection(
    val key: String,
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
    overviewSections: List<OverviewSection> = emptyList(),
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

    // Screenshot-only: pre-select an extra tab by label so the generator can capture
    // detail tabs (Usage, Rules, Bindings, Endpoints) without user interaction.
    // Inert when autoTab map is empty (normal use). Keys on name/namespace/tabs.size
    // so this effect re-runs AFTER the LaunchedEffect(name, namespace) above resets
    // to page 0, ensuring the desired tab is re-applied following a selection change.
    val autoTabMap by ScreenshotHooks.autoTab.collectAsState()
    LaunchedEffect(name, namespace, tabs.size, autoTabMap) {
        val label = autoTabMap[kind] ?: return@LaunchedEffect
        val idx = tabs.indexOfFirst { it.label == label }
        if (idx > 0) {
            activeTab = idx
            pagerState.scrollToPage(idx)
        }
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
                        sections = overviewSections,
                    )

                    page == yamlIndex -> GenericYamlTab(kind, name, namespace, apiGroup, apiVersion, plural)

                    else -> extraTabs[page - 1].content()
                }
            }
        }
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
                        val isNone = f.value == NONE_PLACEHOLDER
                        Text(
                            if (isNone) EMPTY_DASH else f.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isNone) KdTextSecondary else KdTextPrimary,
                            fontWeight = FontWeight.Medium,
                        )
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
    sections: List<OverviewSection> = emptyList(),
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

        sections.forEach { it.content() }
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
    val copyToClipboard = rememberCopyToClipboard()
    var yaml by remember(kind, name, namespace) { mutableStateOf<String?>(null) }
    var loading by remember(kind, name, namespace) { mutableStateOf(true) }
    LaunchedEffect(kind, name, namespace, apiGroup, apiVersion) {
        loading = true
        yaml = withContext(Dispatchers.IO) {
            kubeClient.getResourceYaml(kind, name, namespace, apiGroup, apiVersion, plural)
        }
        loading = false
    }

    val isSecret = SecretYamlMasking.isSecretKind(kind)
    val maskPref by PreferenceRepository.maskSecretValues.collectAsState()
    // One toggle shared by both modes. Masked mode: Reveal shows the decoded value
    // vs ••••••. Unmasked mode: Decode shows the decoded value vs raw base64.
    var showDecoded by remember(kind, name, namespace) { mutableStateOf(false) }
    // Reset to each mode's default whenever the global masking setting flips, so
    // toggling it never strands the panel on a stale decoded view.
    LaunchedEffect(maskPref) { showDecoded = false }

    val raw = yaml ?: ""
    val displayText = remember(raw, isSecret, maskPref, showDecoded) {
        when {
            !isSecret -> raw
            maskPref && showDecoded -> SecretYamlMasking.revealSecretYaml(raw)
            maskPref -> SecretYamlMasking.maskSecretYaml(raw)
            showDecoded -> SecretYamlMasking.revealSecretYaml(raw)
            else -> raw
        }
    }
    val lines = remember(displayText) { displayText.lines() }

    var query by remember(kind, name, namespace) { mutableStateOf("") }
    val matches = remember(lines, query) { findYamlMatches(lines, query) }
    var current by remember(lines, query) { mutableIntStateOf(0) }
    val goNext = { if (matches.isNotEmpty()) current = (current + 1) % matches.size }
    val goPrev = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size }

    // Keyed on resource identity (not displayText) so a Reveal/Decode toggle does not
    // reset the scroll (an active search may still re-centre its match), while selecting
    // a different resource does — this composable is recomposed in place, never
    // recreated, on selection change.
    val vScroll = remember(kind, name, namespace) { ScrollState(0) }
    val hScroll = remember(kind, name, namespace) { ScrollState(0) }

    // Set from the first content line's onTextLayout; used to scroll a match into view
    // without measuring the viewport (ScrollState.viewportSize is already the viewport).
    var lineHeightPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(current, matches, lineHeightPx) {
        val m = matches.getOrNull(current) ?: return@LaunchedEffect
        if (lineHeightPx <= 0) return@LaunchedEffect
        val target = (m.line * lineHeightPx - vScroll.viewportSize / 2 + lineHeightPx / 2)
            .coerceIn(0, vScroll.maxValue)
        vScroll.animateScrollTo(target)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YamlSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).height(34.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Enter && event.isShiftPressed -> {
                                goPrev()
                                true
                            }

                            event.key == Key.Enter -> {
                                goNext()
                                true
                            }

                            // Only consume Escape while it has something to clear, so a
                            // future panel-level Escape handler is not pre-empted.
                            event.key == Key.Escape && query.isNotEmpty() -> {
                                query = ""
                                true
                            }

                            else -> false
                        }
                    },
            )
            if (query.isNotBlank()) {
                Text(
                    "${if (matches.isEmpty()) 0 else current + 1}/${matches.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                )
            }
            IconButton(onClick = goPrev, modifier = Modifier.size(24.dp), enabled = matches.isNotEmpty()) {
                Icon(
                    painterResource(Res.drawable.keyboard_arrow_up_filled),
                    "Previous match",
                    Modifier.size(14.dp),
                    tint = KdTextSecondary.copy(alpha = if (matches.isEmpty()) 0.3f else 1f),
                )
            }
            IconButton(onClick = goNext, modifier = Modifier.size(24.dp), enabled = matches.isNotEmpty()) {
                Icon(
                    painterResource(Res.drawable.keyboard_arrow_down_filled),
                    "Next match",
                    Modifier.size(14.dp),
                    tint = KdTextSecondary.copy(alpha = if (matches.isEmpty()) 0.3f else 1f),
                )
            }
            if (isSecret) {
                // Masked mode → Reveal/Hide (shield); unmasked mode → Decode/Raw (swap).
                val (buttonIcon, buttonLabel) = when {
                    maskPref && showDecoded -> Res.drawable.security_filled to "Hide"
                    maskPref -> Res.drawable.security_filled to "Reveal"
                    showDecoded -> Res.drawable.swap_horiz_filled to "Raw"
                    else -> Res.drawable.swap_horiz_filled to "Decode"
                }
                TextButton(
                    onClick = { showDecoded = !showDecoded },
                    colors = ButtonDefaults.textButtonColors(contentColor = KdTextSecondary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(painterResource(buttonIcon), null, Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(buttonLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
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
            // Hoisted so kdMonoFamily() + TextStyle.copy() run once per composition
            // (not once per line) and the gutter/content Texts provably share one style.
            val lineStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = kdMonoFamily(),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            val matchesByLine = remember(matches) { matches.groupBy { it.line } }
            val cur = matches.getOrNull(current)
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    // Line-number gutter: outside SelectionContainer, not selectable.
                    // Shares vScroll with the content column below — never add
                    // padding/spacing/a trailing element to one without the other,
                    // or their content heights (and thus scroll sync) drift apart.
                    Column(Modifier.verticalScroll(vScroll)) {
                        repeat(lines.size) { i ->
                            Text(
                                "${i + 1}",
                                modifier = Modifier.width(36.dp),
                                style = lineStyle,
                                color = KdTextSecondary.copy(alpha = 0.35f),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    SelectionContainer(Modifier.weight(1f)) {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(vScroll).horizontalScroll(hScroll),
                        ) {
                            lines.forEachIndexed { i, line ->
                                val ranges = matchesByLine[i]
                                val curRange = cur?.takeIf { it.line == i }?.range
                                val text = remember(line, ranges, curRange) {
                                    buildAnnotatedString {
                                        append(highlightYamlLine(line))
                                        // highlightYamlLine rebuilds a line whose indent
                                        // contains non-space whitespace (e.g. a tab)
                                        // shorter than the original, so clamp the raw-line
                                        // match indices to the built length.
                                        val n = length
                                        ranges?.forEach {
                                            addStyle(
                                                SpanStyle(background = KdWarning.copy(alpha = 0.35f)),
                                                it.range.first.coerceAtMost(n),
                                                (it.range.last + 1).coerceAtMost(n),
                                            )
                                        }
                                        curRange?.let {
                                            addStyle(
                                                SpanStyle(background = KdWarning.copy(alpha = 0.7f)),
                                                it.first.coerceAtMost(n),
                                                (it.last + 1).coerceAtMost(n),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = text,
                                    style = lineStyle,
                                    softWrap = false,
                                    // Text(AnnotatedString, ...) requires a non-nullable
                                    // onTextLayout; a no-op callback for every line but the
                                    // first keeps lineHeightPx sourced from line 0 only.
                                    onTextLayout = if (i == 0) {
                                        { r -> lineHeightPx = r.size.height }
                                    } else {
                                        {}
                                    },
                                )
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(vScroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(hScroll),
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 12.dp),
                )
            }
        }
    }
}

// Copied from LogViewer.kt's FilterLogsTextField pattern (intentionally not shared) so
// the YAML pane doesn't take a dependency on the log viewer package.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YamlSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
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
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            color = KdTextPrimary,
            fontFamily = kdMonoFamily(),
        ),
        cursorBrush = SolidColor(KdPrimary),
        interactionSource = interactionSource,
        modifier = modifier,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = {
                    Text("Search YAML…", style = MaterialTheme.typography.bodySmall, color = KdTextPlaceholder)
                },
                leadingIcon = {
                    Icon(painterResource(Res.drawable.search_filled), null, Modifier.size(16.dp), tint = KdTextPlaceholder)
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
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
