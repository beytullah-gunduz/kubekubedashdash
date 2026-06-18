package com.kubekubedashdash.ui.screens.services.viewmodel

import com.kubekubedashdash.models.ServiceInfo
import com.kubekubedashdash.ui.screens.viewmodel.ResourceListViewModel
import com.kubekubedashdash.util.ReactiveKubeClient

class ServicesScreenViewModel(
    reactiveClient: ReactiveKubeClient,
) : ResourceListViewModel<ServiceInfo>(reactiveClient.services)
