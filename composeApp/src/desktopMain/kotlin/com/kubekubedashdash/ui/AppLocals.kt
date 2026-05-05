package com.kubekubedashdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kubekubedashdash.model.ClusterSession

/** Conditionally wrap [content] in a [CompositionLocalProvider] for [session] when non-null. */
@Composable
internal fun MaybeProvideSessionLocals(session: ClusterSession?, content: @Composable () -> Unit) {
    if (session != null) {
        val isConnected by session.viewModel.isConnected.collectAsState()
        val connectionError by session.viewModel.connectionError.collectAsState()
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides session,
            LocalReactiveKubeClient provides session.reactiveClient,
            LocalIsConnected provides isConnected,
            LocalConnectionError provides connectionError,
        ) { content() }
    } else {
        content()
    }
}
