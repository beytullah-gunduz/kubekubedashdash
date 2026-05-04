package com.kubekubedashdash.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.kubekubedashdash.util.ReactiveKubeClient

/**
 * The active session's [ReactiveKubeClient], scoped per OS window via
 * [androidx.compose.runtime.CompositionLocalProvider] in [App].
 *
 * Screens and their ViewModels MUST reach the kube client through this local
 * — the legacy [com.kubekubedashdash.services.KubeClientService] facade is a
 * single global pointer and would tie every window to the same cluster
 * connection, defeating multi-window. Read it in the screen composable, then
 * pass it to the screen's ViewModel via the `viewModel { … }` initializer so
 * the VM constructor captures the right client.
 */
val LocalReactiveKubeClient = staticCompositionLocalOf<ReactiveKubeClient> {
    error("No ReactiveKubeClient — wrap content in CompositionLocalProvider with the active session's reactiveClient")
}

val LocalIsConnected = staticCompositionLocalOf { false }
val LocalConnectionError = staticCompositionLocalOf<String?> { null }
