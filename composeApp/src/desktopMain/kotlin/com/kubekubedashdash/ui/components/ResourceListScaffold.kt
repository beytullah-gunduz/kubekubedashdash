package com.kubekubedashdash.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.kubekubedashdash.models.ResourceState

/** Loading/Error/Success switch shared by simple list screens. The success slot receives the
 *  raw data; the screen computes its filtered list INSIDE the slot (so remember-keys stay correct). */
@Composable
fun <T> ResourceListScaffold(
    state: ResourceState<List<T>>,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { SkeletonRows() },
    error: @Composable (String) -> Unit = { ResourceErrorMessage(it) },
    success: @Composable (data: List<T>) -> Unit,
) {
    when (val s = state) {
        is ResourceState.Loading -> loading()
        is ResourceState.Error -> error(s.message)
        is ResourceState.Success -> success(s.data)
    }
}

/** Keyed filter helper. CRITICAL: `predicate` is NOT a remember key (it's a fresh lambda each
 *  recomposition); only the value-keys drive recompute — byte-identical to the current behavior. */
@Composable
fun <T> rememberResourceFilter(
    data: List<T>,
    searchQuery: String,
    labelSelector: Map<String, String>,
    annotationSelector: Map<String, String>,
    vararg extraKeys: Any?,
    predicate: (item: T, search: String, labels: Map<String, String>, annotations: Map<String, String>) -> Boolean,
): List<T> = remember(data, searchQuery, labelSelector, annotationSelector, *extraKeys) {
    data.filter { predicate(it, searchQuery, labelSelector, annotationSelector) }
}
