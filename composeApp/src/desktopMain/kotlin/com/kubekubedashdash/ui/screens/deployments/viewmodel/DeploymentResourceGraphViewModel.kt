package com.kubekubedashdash.ui.screens.deployments.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.ResourceGraph
import com.kubekubedashdash.models.ResourceGraphNode
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeploymentResourceGraphViewModel(
    private val reactiveClient: ReactiveKubeClient,
) : ViewModel() {
    private val _graph = MutableStateFlow<ResourceGraph?>(null)
    val graph: StateFlow<ResourceGraph?> = _graph.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loadJob: Job? = null

    fun load(deploymentName: String, namespace: String) {
        // Cancel any in-flight load so a slow earlier request can't finish after
        // a newer one and overwrite its graph (last-request-wins).
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _graph.value = withContext(Dispatchers.IO) {
                    reactiveClient.getDeploymentResourceGraph(deploymentName, namespace)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load resource graph"
            }
            _loading.value = false
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
