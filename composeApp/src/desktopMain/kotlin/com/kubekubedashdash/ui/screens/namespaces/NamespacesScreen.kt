package com.kubekubedashdash.ui.screens.namespaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.screens.namespaces.viewmodel.NamespacesScreenViewModel

@Composable
fun NamespacesScreen(
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
    viewModel: NamespacesScreenViewModel = viewModel { NamespacesScreenViewModel() },
) {
    val state by viewModel.state.collectAsState()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val filtered = s.data.filter { ns ->
                searchQuery.isBlank() ||
                    ns.name.contains(searchQuery, ignoreCase = true) ||
                    (ns.status?.contains(searchQuery, ignoreCase = true) ?: false)
            }

            Column(modifier = Modifier.fillMaxSize()) {
                ResourceCountHeader(filtered.size, "Namespaces")
                NamespaceTable(
                    namespaces = filtered,
                    selectedUid = selectedUid,
                    onClick = { ns ->
                        selectedUid = ns.uid
                        onNavigate(Screen.Detail.NamespaceDetail(ns))
                    },
                )
            }
        }
    }
}
