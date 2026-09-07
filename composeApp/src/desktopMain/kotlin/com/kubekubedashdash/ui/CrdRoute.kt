package com.kubekubedashdash.ui

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.ResourceState

/**
 * What a CustomResource tab should show, decided from the cluster's CRD list.
 * A restored tab whose CRD has since been uninstalled used to park on the
 * Connecting spinner forever, because the router could not tell "the list has
 * not synced yet" from "the list synced and the CRD is not in it".
 */
sealed interface CrdRoute {
    /** The CRD list has not synced (also briefly on every reconnect). */
    data object Loading : CrdRoute

    data class Found(val crd: CrdInfo) : CrdRoute

    /** Synced, and the CRD is not there — or the list could not be read at all. */
    data class Missing(val reason: String) : CrdRoute
}

internal fun crdRoute(state: ResourceState<List<CrdInfo>>, target: Screen.Main.CustomResource): CrdRoute = when (state) {
    ResourceState.Loading -> CrdRoute.Loading

    is ResourceState.Error -> CrdRoute.Missing("Custom resource definitions could not be listed: ${state.message}")

    is ResourceState.Success ->
        state.data
            .firstOrNull { it.group == target.group && it.kind == target.kind }
            ?.let { CrdRoute.Found(it) }
            ?: if (state.data.isEmpty()) {
                // The informer could not be created (typically RBAC): the flow
                // emits an empty Success rather than an Error.
                CrdRoute.Missing("No custom resource definitions are visible on this cluster: none are installed, listing them is not permitted, or the list could not be read.")
            } else {
                CrdRoute.Missing("${target.kind} (${target.group}) is not installed on this cluster. It may have been removed since this tab was saved.")
            }
}
