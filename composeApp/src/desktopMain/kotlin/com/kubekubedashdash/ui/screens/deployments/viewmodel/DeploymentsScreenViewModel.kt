package com.kubekubedashdash.ui.screens.deployments.viewmodel

import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.ui.screens.viewmodel.ResourceListViewModel
import com.kubekubedashdash.util.ReactiveKubeClient

class DeploymentsScreenViewModel(
    reactiveClient: ReactiveKubeClient,
) : ResourceListViewModel<DeploymentInfo>(reactiveClient.deployments)
