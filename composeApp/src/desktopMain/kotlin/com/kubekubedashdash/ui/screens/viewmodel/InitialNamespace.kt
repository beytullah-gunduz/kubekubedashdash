package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.models.NamespaceScope

/**
 * The namespaces a fresh connect lands on: the restore target, else the
 * cluster's default namespace, else all. A blank default counts as absent.
 */
fun initialNamespaceScope(restore: NamespaceScope?, defaultNamespace: String?): NamespaceScope = restore
    ?: defaultNamespace?.takeIf { it.isNotBlank() }?.let { NamespaceScope.single(it) }
    ?: NamespaceScope.All
