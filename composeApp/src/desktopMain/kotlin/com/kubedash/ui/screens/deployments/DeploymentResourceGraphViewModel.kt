package com.kubedash.ui.screens.deployments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.KubeClient
import com.kubedash.ResourceGraph
import com.kubedash.ResourceGraphNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeploymentResourceGraphViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    var graph by mutableStateOf<ResourceGraph?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load(deploymentName: String, namespace: String) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                graph = withContext(Dispatchers.IO) {
                    kubeClient.getDeploymentResourceGraph(deploymentName, namespace)
                }
            } catch (e: Exception) {
                error = e.message ?: "Failed to load resource graph"
            }
            loading = false
        }
    }

    companion object {
        val kindLayerOrder = mapOf(
            "Ingress" to 0,
            "Service" to 1,
            "HPA" to 2,
            "Deployment" to 3,
            "ReplicaSet" to 4,
            "Pod" to 5,
            "ConfigMap" to 6,
            "Secret" to 6,
            "PVC" to 6,
            "ServiceAccount" to 6,
        )

        fun groupIntoLayers(graph: ResourceGraph): List<List<ResourceGraphNode>> = graph.nodes
            .groupBy { kindLayerOrder[it.kind] ?: 99 }
            .toSortedMap()
            .values
            .toList()
    }
}
