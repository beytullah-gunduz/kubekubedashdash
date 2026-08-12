package com.kubekubedashdash.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Multi-select state for a resource list. Every write is intersected with
 * the last set of ids the screen reported as visible AND live, so a hidden
 * or departed row can never be selected regardless of which code path sets
 * the selection ("you can only act on what you can see").
 */
class SelectionFunnel {
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private var visible: Set<String> = emptySet()

    fun setVisible(ids: Set<String>) {
        visible = ids
        _selected.update { it intersect ids }
    }

    fun set(ids: Set<String>) {
        _selected.value = ids intersect visible
    }

    fun reset() {
        visible = emptySet()
        _selected.value = emptySet()
    }
}
