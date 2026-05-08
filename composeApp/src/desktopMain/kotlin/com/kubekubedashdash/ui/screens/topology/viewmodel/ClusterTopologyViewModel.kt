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
            val nodeToCol = HashMap<String, Int>(graph.nodes.size)
            graph.nodes.forEach { node ->
                val col = kindColumnOrder[node.kind] ?: 2
                columns[col].add(node)
                nodeToCol[node.id] = col
            }

            // Per-node "upstream connection count": edges where the other end
            // sits in a column to this one's left. In big namespaces most
            // workloads have no Service in front of them; without this the few
            // workloads that *do* land at whatever index they happened to take
            // in graph.nodes (often the top), forcing the user to scroll past
            // a wall of unconnected workloads to find the ones the services
            // actually point to. Downstream pod fan-out is excluded by the
            // column-direction check — pods sit in a higher-indexed column
            // than their workload, so a workload's edges to its own pods
            // never bump its own upstream count.
            val upstreamDegree = HashMap<String, Int>()
            graph.edges.forEach { edge ->
                val srcCol = nodeToCol[edge.sourceId] ?: return@forEach
                val tgtCol = nodeToCol[edge.targetId] ?: return@forEach
                when {
                    srcCol < tgtCol -> upstreamDegree.merge(edge.targetId, 1) { a, b -> a + b }
                    tgtCol < srcCol -> upstreamDegree.merge(edge.sourceId, 1) { a, b -> a + b }
                }
            }

            return columns.mapIndexed { colIdx, col ->
                // Pod column gets skipped: every visible pod has exactly one
                // upstream link (to its parent workload), so degree-based
                // reorder would only scatter pods of the same workload that
                // the user expects to see grouped together.
                if (colIdx == 4 || col.size <= 1) return@mapIndexed col.toList()
                val degrees = col.map { upstreamDegree[it.id] ?: 0 }
                if (degrees.min() == degrees.max()) {
                    col.toList()
                } else {
                    arrangeAroundCenter(col.sortedByDescending { upstreamDegree[it.id] ?: 0 })
                }
            }
        }

        // Place sorted-desc nodes so the top item lands at the visual center
        // and each subsequent item alternates one slot below, then above. The
        // result is a pyramid: most-connected in the middle, least-connected
        // at the edges. Even-sized columns bias upward by half a slot.
        private fun <T> arrangeAroundCenter(sortedDesc: List<T>): List<T> {
            val n = sortedDesc.size
            if (n <= 1) return sortedDesc
            val out = MutableList<T?>(n) { null }
            val center = (n - 1) / 2
            sortedDesc.forEachIndexed { i, item ->
                val pos = when {
                    i == 0 -> center
                    i % 2 == 1 -> center + (i + 1) / 2
                    else -> center - i / 2
                }
                out[pos] = item
            }
            @Suppress("UNCHECKED_CAST")
            return out as List<T>
        }
    }
}
