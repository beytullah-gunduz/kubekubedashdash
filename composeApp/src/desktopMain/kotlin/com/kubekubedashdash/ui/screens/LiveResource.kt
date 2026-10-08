package com.kubekubedashdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.models.ResourceState

/** The freshest known copy of a resource a detail panel is showing, plus whether it has been deleted. */
data class LiveResource<T>(val value: T, val removed: Boolean)

/** The deleted resource a detail panel is showing; see [LocalRemovedResource]. */
data class RemovedResource(val kind: String, val name: String)

/**
 * Set by [LiveDetailPane] while its resource is deleted from the cluster, null otherwise
 * and outside a live pane. [DetailPanelHeader] reads it to disable its verbs and to show
 * the "no longer exists" banner under itself. A plain (non-static) local: it changes only
 * when the resource disappears or comes back.
 */
val LocalRemovedResource = compositionLocalOf<RemovedResource?> { null }

/**
 * Decide whether a resource a detail panel is showing has been deleted from the
 * cluster.
 *
 * Conservative on purpose — only `true` once we have actually observed the
 * resource live during this panel's lifetime ([everSeenLive]) and then seen it
 * vanish from a successfully-loaded list while still [inScope] of the current
 * namespace selection. That rules out three false positives: the pre-load
 * window (state not yet `Success`), a transient `Loading` blip, and a namespace
 * switch that merely moves the resource out of the currently-watched list.
 * [listUpdatedSinceScopeChange] is false while the list in hand is still the one from before
 * [inScope] last changed: a namespace joining the selection flips [inScope] at once, while
 * the list re-filters on its own flow one emission later, so absence proves nothing yet.
 * Limitation: a re-filter whose result equals the list in hand emits nothing
 * (ResourceState.Success is a data class and the list flow is
 * distinctUntilChanged), so a resource deleted while out of scope, in a
 * namespace with no other objects, is reported removed only by the next list
 * change. Preferred over the false "deleted" banner this guard removes.
 */
fun isResourceRemoved(
    state: ResourceState<*>,
    presentNow: Boolean,
    everSeenLive: Boolean,
    inScope: Boolean,
    listUpdatedSinceScopeChange: Boolean = true,
): Boolean = inScope && everSeenLive && !presentNow && state is ResourceState.Success && listUpdatedSinceScopeChange

/**
 * Re-resolve a detail panel's resource against the live list [state] by [uid] so
 * the panel reflects status/field changes instead of the snapshot captured at
 * click time, and reports when the resource is deleted.
 *
 * [inScope] must be true only when the resource could appear in [state] given
 * the current namespace selection (always true for cluster-scoped resources;
 * for namespaced ones, the namespace selection contains the resource's
 * namespace). When out of scope the panel keeps showing the last
 * known copy and never claims the resource was removed.
 */
@Composable
fun <T> rememberLiveResource(
    initial: T,
    state: ResourceState<List<T>>,
    uid: String,
    inScope: Boolean,
    uidOf: (T) -> String,
): LiveResource<T> {
    val live = (state as? ResourceState.Success)?.data?.firstOrNull { uidOf(it) == uid }
    var lastKnown by remember(uid) { mutableStateOf(initial) }
    var everSeenLive by remember(uid) { mutableStateOf(false) }
    // The list in hand when inScope last changed; see isResourceRemoved.
    val stateAtScopeChange = remember(uid, inScope) { state }
    LaunchedEffect(live, inScope) {
        if (inScope && live != null) {
            lastKnown = live
            everSeenLive = true
        }
    }
    return LiveResource(
        value = if (inScope) (live ?: lastKnown) else lastKnown,
        removed = isResourceRemoved(state, live != null, everSeenLive, inScope, listUpdatedSinceScopeChange = state !== stateAtScopeChange),
    )
}

/**
 * Wraps a detail panel so it renders the live copy of its resource (resolved by
 * [uid] from [state]) and tells the panel's header (through
 * [LocalRemovedResource]) when the resource has been deleted from the cluster, so the
 * header can show the banner below itself and disable its verbs. See [rememberLiveResource]
 * for the [inScope] contract.
 */
@Composable
fun <T> LiveDetailPane(
    initial: T,
    state: ResourceState<List<T>>,
    uid: String,
    inScope: Boolean,
    kind: String,
    name: String,
    uidOf: (T) -> String,
    content: @Composable (T) -> Unit,
) {
    val live = rememberLiveResource(initial, state, uid, inScope, uidOf)
    val removed = remember(live.removed, kind, name) { if (live.removed) RemovedResource(kind, name) else null }
    CompositionLocalProvider(LocalRemovedResource provides removed) {
        Box(Modifier.fillMaxSize()) {
            content(live.value)
        }
    }
}

@Composable
internal fun ResourceRemovedBanner(kind: String, name: String) {
    Surface(color = KdError.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("This $kind no longer exists", style = MaterialTheme.typography.labelLarge, color = KdError, fontWeight = FontWeight.SemiBold)
            Text(
                "“$name” was deleted from the cluster — showing the last known state.",
                style = MaterialTheme.typography.bodySmall,
                color = KdError,
            )
        }
    }
}
