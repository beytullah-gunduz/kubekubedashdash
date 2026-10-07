package com.kubekubedashdash.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.orCompact
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.warning_filled
import com.kubekubedashdash.retroCaps
import com.kubekubedashdash.retroChrome
import com.kubekubedashdash.ui.modals.viewmodel.DiscoveryMode
import com.kubekubedashdash.ui.modals.viewmodel.PasteNotice
import org.jetbrains.compose.resources.painterResource

/** Test tags for the "Enter by name" tab and the tab row, shared by the GKE and EKS discovery modals. */
internal object ByNameTags {
    const val TAB_BROWSE = "discovery-tab-browse"
    const val TAB_BY_NAME = "discovery-tab-by-name"
    const val PASTE = "by-name-paste"
    const val IMPORT = "by-name-import"
    const val ADD_ANOTHER = "by-name-add-another"
    const val SWITCH_TO_BY_NAME = "switch-to-by-name"

    fun field(name: String) = "by-name-field-$name" // project, location, region, cluster

    fun suggestion(value: String) = "by-name-suggestion-$value"

    fun profile(name: String) = "by-name-profile-$name"
}

/** The "Browse …" | "Enter by name" tab row on the first step of a discovery modal. */
@Composable
internal fun DiscoveryModeTabs(mode: DiscoveryMode, browseLabel: String, onSelect: (DiscoveryMode) -> Unit) {
    SecondaryTabRow(
        selectedTabIndex = mode.ordinal,
        containerColor = KdSurfaceVariant.copy(alpha = 0.5f),
        contentColor = KdPrimary,
        divider = { HorizontalDivider(color = KdBorder) },
    ) {
        DiscoveryMode.entries.forEach { entry ->
            val label = if (entry == DiscoveryMode.BROWSE) browseLabel else "Enter by name"
            val tag = if (entry == DiscoveryMode.BROWSE) ByNameTags.TAB_BROWSE else ByNameTags.TAB_BY_NAME
            Tab(
                selected = entry == mode,
                onClick = { onSelect(entry) },
                modifier = Modifier.testTag(tag),
                selectedContentColor = KdPrimary,
                unselectedContentColor = KdTextSecondary,
            ) {
                Text(
                    label.retroCaps(),
                    style = MaterialTheme.typography.labelMedium.retroChrome(8.sp),
                    modifier = Modifier.padding(vertical = 10.dp.orCompact(6.dp)),
                )
            }
        }
    }
}

/**
 * The field that takes a pasted command, context name or ARN. The text is only read by the
 * view model's parser and never executed; [notice] says what it understood.
 */
@Composable
internal fun PasteReferenceField(value: String, onValueChange: (String) -> Unit, placeholder: String, notice: PasteNotice?) {
    Text("Paste a command or a context name", color = KdTextSecondary, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag(ByNameTags.PASTE),
        singleLine = true,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = kdMonoFamily()),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KdPrimary,
            unfocusedBorderColor = KdBorder,
            focusedTextColor = KdTextPrimary,
            unfocusedTextColor = KdTextPrimary,
        ),
    )
    if (notice != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            notice.text,
            color = if (notice.recognized) KdTextSecondary else KdWarning,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * A labelled text field with an optional error line and an optional row of clickable
 * suggestion values below it (each one fills the field).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ByNameTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    error: String?,
    tag: String,
    suggestionsLabel: String? = null,
    suggestions: List<String> = emptyList(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KdPrimary,
            unfocusedBorderColor = KdBorder,
            errorBorderColor = KdError,
            focusedTextColor = KdTextPrimary,
            unfocusedTextColor = KdTextPrimary,
        ),
    )
    if (suggestions.isNotEmpty() && suggestionsLabel != null) {
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Same vertical padding as the clickable values, so the label sits on their baseline.
            Text(
                suggestionsLabel,
                color = KdTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            suggestions.forEach { suggestion ->
                Text(
                    suggestion,
                    color = KdPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(4.dp.kdCorner)
                        .clickable { onValueChange(suggestion) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag(ByNameTags.suggestion(suggestion)),
                )
            }
        }
    }
}

@Composable
internal fun ByNameInfoLine(text: String) {
    Text(text, color = KdTextSecondary, style = MaterialTheme.typography.bodySmall)
}

/** The advisory banner of the by-name tab; [action] adds a trailing clickable label. */
@Composable
internal fun ByNameWarningBanner(text: String, action: Pair<String, () -> Unit>? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(8.dp.kdCorner)
            .background(KdWarning.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(Res.drawable.warning_filled), null, tint = KdWarning, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = KdTextPrimary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                action.first,
                color = KdPrimary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(4.dp.kdCorner)
                    .clickable(onClick = action.second)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
