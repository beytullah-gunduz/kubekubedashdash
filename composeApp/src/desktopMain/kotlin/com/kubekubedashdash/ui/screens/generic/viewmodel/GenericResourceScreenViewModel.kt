package com.kubekubedashdash.ui.screens.generic.viewmodel

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.screens.viewmodel.ResourceListViewModel
import kotlinx.coroutines.flow.StateFlow

class GenericResourceScreenViewModel(
    sourceFlow: StateFlow<ResourceState<List<GenericResourceInfo>>>,
) : ResourceListViewModel<GenericResourceInfo>(sourceFlow)
