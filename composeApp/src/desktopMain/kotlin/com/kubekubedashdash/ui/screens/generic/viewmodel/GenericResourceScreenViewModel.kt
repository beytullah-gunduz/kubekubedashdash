package com.kubekubedashdash.ui.screens.generic.viewmodel

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.screens.viewmodel.ResourceListViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GenericResourceScreenViewModel(
    sourceFlow: StateFlow<ResourceState<List<GenericResourceInfo>>>,
) : ResourceListViewModel<GenericResourceInfo>(sourceFlow) {

    /**
     * The detail panel takes the whole content area. Held here, next to the
     * selection it belongs to, so a tab switch (which disposes the screen's
     * composition) does not collapse it.
     */
    private val _detailExpanded = MutableStateFlow(false)
    val detailExpanded: StateFlow<Boolean> = _detailExpanded.asStateFlow()

    fun setDetailExpanded(expanded: Boolean) {
        _detailExpanded.value = expanded
    }
}
