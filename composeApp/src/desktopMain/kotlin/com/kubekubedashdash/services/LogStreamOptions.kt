package com.kubekubedashdash.services

/**
 * Toolbar-driven options for a pod log stream. A plain value — the tab is
 * republished under a new instance rather than mutated in place, see
 * [LogStreamRegistry.setOptions].
 */
data class LogStreamOptions(
    val timestamps: Boolean = false,
    val previous: Boolean = false,
    /** null = no time bound. */
    val sinceSeconds: Int? = null,
)
