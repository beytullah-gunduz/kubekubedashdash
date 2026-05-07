package com.kubekubedashdash.ui.screens.topology.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.ResourceGraph
import com.kubekubedashdash.models.ResourceGraphNode
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClusterTopologyViewModel(
    private val reactiveClient: ReactiveKubeClient,
) : ViewModel() {
    private val _graph = MutableStateFlow<ResourceGraph?>(null)
    val graph: StateFlow<ResourceGraph?> = _graph.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load(namespace: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _graph.value = withContext(Dispatchers.IO) {
                    reactiveClient.getClusterTopologyGraph(namespace)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load topology"
                // Keep the previously-loaded graph visible on refresh failure.
                // Only the first load will leave _graph null, so the screen can
                // still distinguish "no graph yet" from "failed refresh, keep
                // showing the last good graph".
            } finally {
                _loading.value = false
            }
        }
    }

    companion object {
        val kindColumnOrder = mapOf(
            "External" to 0,
            "Ingress" to 1,
            "Service" to 2,
            "WorkloadGroup" to 3,
            "Pod" to 4,
            "ConfigMap" to 5,
            "Secret" to 5,
            "PersistentVolumeClaim" to 5,
            "ServiceAccount" to 5,
        )

        fun groupIntoColumns(graph: ResourceGraph): List<List<ResourceGraphNode>> {
            val columns = Array(6) { mutableListOf<ResourceGraphNode>() }
            graph.nodes.forEach { node ->
                val col = kindColumnOrder[node.kind] ?: 2
                columns[col].add(node)
            }
            return columns.map { it.toList() }
        }
    }
}
