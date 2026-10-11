package com.kubekubedashdash.ui.components

import androidx.compose.runtime.compositionLocalOf

/** Set where a list follows the namespace selector at All Namespaces: a cluster-wide 403 there can be fixed by picking a namespace. */
internal class NamespaceAccessHint(val onChooseNamespace: () -> Unit)

internal val LocalNamespaceAccessHint = compositionLocalOf<NamespaceAccessHint?> { null }
