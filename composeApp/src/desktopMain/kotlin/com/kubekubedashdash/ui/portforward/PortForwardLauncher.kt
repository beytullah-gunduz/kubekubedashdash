package com.kubekubedashdash.ui.portforward

import androidx.compose.runtime.compositionLocalOf
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.services.portforward.PortForwardRequest

fun interface PortForwardLauncher {
    fun launch(request: PortForwardRequest)
}

/** Null outside a cluster page (All Clusters, screenshots): callers hide the verb. */
val LocalPortForwardLauncher = compositionLocalOf<PortForwardLauncher?> { null }

/** Hosted in App.kt: the request plus the cluster page it came from. */
data class PendingPortForward(val session: ClusterSession, val request: PortForwardRequest)
