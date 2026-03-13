package com.kubekubedashdash.ui.screens.deployments

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
import com.kubekubedashdash.ui.screens.deployments.viewmodel.DeploymentsScreenViewModel

@Composable
fun DeploymentsScreen(
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
    viewModel: DeploymentsScreenViewModel = viewModel { DeploymentsScreenViewModel() },
) {
    val state by viewModel.state.collectAsState()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val filtered = s.data.filter { dep ->
                searchQuery.isBlank() ||
                    dep.name.contains(searchQuery, ignoreCase = true) ||
                    dep.namespace.contains(searchQuery, ignoreCase = true)
            }

            Column(modifier = Modifier.fillMaxSize()) {
                ResourceCountHeader(filtered.size, "Deployments")
                DeploymentTable(
                    deployments = filtered,
                    selectedUid = selectedUid,
                    onClick = { dep ->
                        selectedUid = dep.uid
                        onNavigate(Screen.Detail.DeploymentDetail(dep))
                    },
                )
            }
        }
    }
}
