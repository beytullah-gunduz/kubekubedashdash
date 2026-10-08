package com.kubekubedashdash.helm

import com.kubekubedashdash.models.ResourceState

/** The Secret-driver and ConfigMap-driver revision lists, reduced to one outcome for the screen. */
sealed interface HelmSourceState {
    data object Loading : HelmSourceState

    data class Failed(val message: String, val forbidden: Boolean) : HelmSourceState

    data class Ready(val refs: List<HelmRevisionRef>, val warning: String?) : HelmSourceState
}

private const val CONFIGMAP_LIST_FAILED = "Releases stored in ConfigMaps are not shown: the ConfigMap list failed."
private const val SECRET_LIST_FAILED = "Releases stored in Secrets are not shown: the Secret list failed."

/**
 * Secrets are the default driver, so they decide the outcome: a ConfigMap list that is still
 * loading or has failed never blocks them. A non-403 failure shows fabric8's raw text, like
 * every other list screen; only the 403 text is replaced, because it names the user's identity.
 */
fun combineHelmSources(
    secrets: ResourceState<List<HelmRevisionRef>>,
    configMaps: ResourceState<List<HelmRevisionRef>>,
): HelmSourceState = when (secrets) {
    ResourceState.Loading -> HelmSourceState.Loading

    is ResourceState.Success -> when (configMaps) {
        is ResourceState.Success -> HelmSourceState.Ready(secrets.data + configMaps.data, null)

        ResourceState.Loading -> HelmSourceState.Ready(secrets.data, null)

        is ResourceState.Error -> HelmSourceState.Ready(
            secrets.data,
            if (isHelmForbidden(configMaps.message)) HELM_CONFIGMAP_FORBIDDEN_WARNING else CONFIGMAP_LIST_FAILED,
        )
    }

    is ResourceState.Error -> {
        val forbidden = isHelmForbidden(secrets.message)
        if (configMaps is ResourceState.Success && configMaps.data.isNotEmpty()) {
            HelmSourceState.Ready(configMaps.data, if (forbidden) HELM_FORBIDDEN_MESSAGE else SECRET_LIST_FAILED)
        } else {
            HelmSourceState.Failed(if (forbidden) HELM_FORBIDDEN_MESSAGE else secrets.message, forbidden)
        }
    }
}
