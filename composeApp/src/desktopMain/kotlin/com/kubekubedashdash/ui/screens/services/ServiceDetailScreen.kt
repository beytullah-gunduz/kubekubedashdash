package com.kubekubedashdash.ui.screens.services

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ServiceInfo
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.settings_ethernet_filled
import com.kubekubedashdash.services.portforward.PortForwardRequest
import com.kubekubedashdash.ui.portforward.LocalPortForwardLauncher
import com.kubekubedashdash.ui.screens.DetailAction
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ResourceDetailPanel

@Composable
fun ServiceDetailScreen(
    service: ServiceInfo,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
    labelQuery: String = "",
    onToggleLabel: (String, String) -> Unit = { _, _ -> },
    annotationQuery: String = "",
    onToggleAnnotation: (String, String) -> Unit = { _, _ -> },
) {
    val selectorFields = service.selector.map { (k, v) -> DetailField("Selector", "$k=$v") }
    val portForward = LocalPortForwardLauncher.current
    val forwardable = service.type != "ExternalName" && service.selector.isNotEmpty()
    val actions = listOfNotNull(
        portForward?.let { launcher ->
            DetailAction(
                icon = Res.drawable.settings_ethernet_filled,
                label = "Port forward",
                description = if (forwardable) {
                    "Forward a local port (127.0.0.1 only) to this service, through one ready pod behind it. Running forwards are listed in the bottom drawer."
                } else {
                    "Not available: this service has no pod selector."
                },
                enabled = forwardable,
                onClick = { launcher.launch(PortForwardRequest.forService(service)) },
            )
        },
    )

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
        annotations = service.annotations,
        onClose = onClose,
        modifier = Modifier.fillMaxSize(),
        labelQuery = labelQuery,
        onToggleLabel = onToggleLabel,
        annotationQuery = annotationQuery,
        onToggleAnnotation = onToggleAnnotation,
        actions = actions,
    )
}
