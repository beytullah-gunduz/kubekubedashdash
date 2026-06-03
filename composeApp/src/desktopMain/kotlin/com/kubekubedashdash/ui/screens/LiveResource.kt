package com.kubekubedashdash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.models.ResourceState

/** The freshest known copy of a resource a detail panel is showing, plus whether it has been deleted. */
data class LiveResource<T>(val value: T, val removed: Boolean)

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
 */
fun isResourceRemoved(
    state: ResourceState<*>,
    presentNow: Boolean,
    everSeenLive: Boolean,
    inScope: Boolean,
): Boolean = inScope && everSeenLive && !presentNow && state is ResourceState.Success

/**
 * Re-resolve a detail panel's resource against the live list [state] by [uid] so
 * the panel reflects status/field changes instead of the snapshot captured at
 * click time, and reports when the resource is deleted.
 *
 * [inScope] must be true only when the resource could appear in [state] given
 * the current namespace selection (always true for cluster-scoped resources;
 * for namespaced ones, the selected namespace is "all" or matches the
 * resource's namespace). When out of scope the panel keeps showing the last
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
    LaunchedEffect(live, inScope) {
        if (inScope && live != null) {
            lastKnown = live
            everSeenLive = true
        }
    }
    return LiveResource(
        value = if (inScope) (live ?: lastKnown) else lastKnown,
        removed = isResourceRemoved(state, live != null, everSeenLive, inScope),
    )
}

/**
 * Wraps a detail panel so it renders the live copy of its resource (resolved by
 * [uid] from [state]) and shows a banner when the resource has been deleted from
 * the cluster. See [rememberLiveResource] for the [inScope] contract.
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
    Column(Modifier.fillMaxSize()) {
        if (live.removed) {
            ResourceRemovedBanner(kind = kind, name = name)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            content(live.value)
        }
    }
}

@Composable
private fun ResourceRemovedBanner(kind: String, name: String) {
    Surface(color = KdError.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
