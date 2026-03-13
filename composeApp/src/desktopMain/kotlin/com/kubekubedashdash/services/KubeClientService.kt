package com.kubekubedashdash.services

import com.kubekubedashdash.util.KubeClient
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

object KubeClientService {
    private val serviceScope = CoroutineScope(SupervisorJob())

    val connectionManager: KubeConnectionManager = KubeConnectionManager()

    val reactiveClient: ReactiveKubeClient = ReactiveKubeClient(
        scope = serviceScope,
        connectionManager = connectionManager,
    )

    val client: KubeClient = KubeClient(connectionManager = connectionManager)
}
