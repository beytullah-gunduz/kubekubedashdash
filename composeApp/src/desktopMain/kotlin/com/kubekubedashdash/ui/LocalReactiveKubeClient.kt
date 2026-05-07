package com.kubekubedashdash.ui

import androidx.compose.runtime.compositionLocalOf
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
 *
 * Uses [compositionLocalOf] (not `staticCompositionLocalOf`) because the
 * provided value swaps every time the active session changes — opening a new
 * cluster, switching tabs, etc. With the static variant, every composable in
 * scope of the provider gets invalidated on each swap regardless of whether it
 * reads the local. See `.docs/feature/second-cluster-pick-perf.md`.
 */
val LocalReactiveKubeClient = compositionLocalOf<ReactiveKubeClient> {
    error("No ReactiveKubeClient — wrap content in CompositionLocalProvider with the active session's reactiveClient")
}

val LocalIsConnected = compositionLocalOf { false }
val LocalConnectionError = compositionLocalOf<String?> { null }
