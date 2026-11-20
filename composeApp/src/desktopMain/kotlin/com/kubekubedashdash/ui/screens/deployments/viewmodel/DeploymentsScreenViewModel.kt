package com.kubekubedashdash.ui.screens.deployments.viewmodel

import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.ui.screens.deployments.BulkScaleRun
import com.kubekubedashdash.ui.screens.viewmodel.ResourceListViewModel
import com.kubekubedashdash.util.ReactiveKubeClient

class DeploymentsScreenViewModel(
    reactiveClient: ReactiveKubeClient,
) : ResourceListViewModel<DeploymentInfo>(reactiveClient.deployments) {
    /** D9: the bulk Scale run in flight or last finished; survives screen recomposition and reattach. */
    var bulkScaleRun: BulkScaleRun? = null
}
