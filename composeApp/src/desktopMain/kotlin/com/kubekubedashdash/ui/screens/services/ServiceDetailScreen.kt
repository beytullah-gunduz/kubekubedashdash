package com.kubekubedashdash.ui.screens.services

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ServiceInfo
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ResourceDetailPanel

@Composable
fun ServiceDetailScreen(
    service: ServiceInfo,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
) {
    val selectorFields = service.selector.map { (k, v) -> DetailField("Selector", "$k=$v") }

    ResourceDetailPanel(
        kind = "Service",
        name = service.name,
        namespace = service.namespace,
        status = service.type,
        fields = listOf(
            DetailField("Type", service.type, serviceTypeColor(service.type)),
            DetailField("Namespace", service.namespace),
            DetailField("Cluster IP", service.clusterIP),
            DetailField("Ports", service.ports),
            DetailField("Age", service.age),
        ) + selectorFields,
        labels = service.labels,
        onClose = onClose,
        modifier = Modifier.fillMaxSize(),
    )
}
