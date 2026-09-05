package com.kubekubedashdash.ui.screens.viewmodel

/**
 * The namespace a fresh connect lands on: the restore target, else the
 * cluster's default, else all. Blank counts as absent.
 */
fun initialNamespace(restoreNamespace: String?, defaultNamespace: String?): String = restoreNamespace?.takeIf { it.isNotBlank() }
    ?: defaultNamespace?.takeIf { it.isNotBlank() }
    ?: "All Namespaces"
