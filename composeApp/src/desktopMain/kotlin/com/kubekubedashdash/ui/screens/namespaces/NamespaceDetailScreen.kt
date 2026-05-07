package com.kubekubedashdash.ui.screens.namespaces

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ResourceDetailPanel

@Composable
fun NamespaceDetailScreen(
    namespace: GenericResourceInfo,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
    labelQuery: String = "",
    onToggleLabel: (String, String) -> Unit = { _, _ -> },
    annotationQuery: String = "",
    onToggleAnnotation: (String, String) -> Unit = { _, _ -> },
) {
    ResourceDetailPanel(
        kind = "Namespace",
        name = namespace.name,
        namespace = null,
        status = namespace.status,
        fields = listOf(
            DetailField("Status", namespace.status ?: "Unknown", namespace.status?.let { statusColor(it) }),
            DetailField("Age", namespace.age),
        ),
        labels = namespace.labels,
        annotations = namespace.annotations,
        onClose = onClose,
        modifier = Modifier.fillMaxSize(),
        labelQuery = labelQuery,
        onToggleLabel = onToggleLabel,
        annotationQuery = annotationQuery,
        onToggleAnnotation = onToggleAnnotation,
    )
}
