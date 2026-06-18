package com.kubekubedashdash.ui.screens.namespaces.viewmodel

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.ui.screens.viewmodel.ResourceListViewModel
import com.kubekubedashdash.util.ReactiveKubeClient

class NamespacesScreenViewModel(
    reactiveClient: ReactiveKubeClient,
) : ResourceListViewModel<GenericResourceInfo>(reactiveClient.namespaces)
