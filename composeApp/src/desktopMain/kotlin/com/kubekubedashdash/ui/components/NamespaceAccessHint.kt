package com.kubekubedashdash.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.ui.showsNamespaceSelector

/** Set where a list follows the namespace selector at All Namespaces: a cluster-wide 403 there can be fixed by picking a namespace. */
internal class NamespaceAccessHint(val onChooseNamespace: () -> Unit)

internal val LocalNamespaceAccessHint = compositionLocalOf<NamespaceAccessHint?> { null }

/**
 * Whether [screen] lists cluster-wide at [scope]: the list follows the selector and the selection is
 * not a single namespace. A selection of two or more namespaces is watched cluster-wide as well.
 */
internal fun namespaceAccessHintApplies(screen: Screen, scope: NamespaceScope): Boolean = screen.showsNamespaceSelector() && scope.serverNamespace == null
