package com.kubekubedashdash.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The shared label/annotation/(status)/clear filter-chip row used in every resource list header. */
@Composable
fun ResourceFilterChips(
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    compact: Boolean,
    pulseLabelsOnEntry: Boolean = false,
    pulseAnnotationsOnEntry: Boolean = false,
    labelOptions: (() -> List<MapSelectorOption>)? = null,
    annotationOptions: (() -> List<MapSelectorOption>)? = null,
    statusChip: (@Composable () -> Unit)? = null,
    clearVisible: Boolean = labelQuery.isNotBlank() || annotationQuery.isNotBlank(),
    onClear: () -> Unit = {
        onLabelQueryChange("")
        onAnnotationQueryChange("")
    },
) {
    LabelSelectorChip(
        query = labelQuery,
        onQueryChange = onLabelQueryChange,
        modifier = Modifier.padding(end = 8.dp),
        pulseOnEntry = pulseLabelsOnEntry,
        compact = compact,
        options = labelOptions,
    )
    AnnotationSelectorChip(
        query = annotationQuery,
        onQueryChange = onAnnotationQueryChange,
        modifier = Modifier.padding(end = 8.dp),
        pulseOnEntry = pulseAnnotationsOnEntry,
        compact = compact,
        options = annotationOptions,
    )
    statusChip?.invoke()
    AnimatedVisibility(
        visible = clearVisible,
        enter = expandHorizontally() + fadeIn(),
        exit = shrinkHorizontally() + fadeOut(),
    ) {
        ClearFiltersChip(
            onClick = onClear,
            modifier = Modifier.padding(end = 8.dp),
            compact = compact,
        )
    }
}
