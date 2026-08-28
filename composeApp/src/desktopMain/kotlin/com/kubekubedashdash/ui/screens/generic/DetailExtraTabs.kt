package com.kubekubedashdash.ui.screens.generic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.account_tree_filled
import com.kubekubedashdash.resources.monitor_heart_filled
import com.kubekubedashdash.resources.security_filled
import com.kubekubedashdash.resources.settings_ethernet_filled
import com.kubekubedashdash.ui.components.EmptyState
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.screens.ExtraTab
import com.kubekubedashdash.ui.screens.OverviewSection
import com.kubekubedashdash.util.EndpointSliceDetail
import com.kubekubedashdash.util.PolicyRuleRow
import com.kubekubedashdash.util.QuotaUsageRow
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.RoleBindingDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Returns kind-specific extra tabs for [ResourceDetailPanel].
 *
 * Extension point: add new `when` branches here as more kind-specific tabs are needed.
 * Signature: (kind, selected resource, kube client, onNavigate) → list of [ExtraTab] (may be empty).
 */
fun kindExtraTabs(
    kind: String,
    res: GenericResourceInfo,
    client: ReactiveKubeClient,
    onNavigate: (Screen) -> Unit = {},
): List<ExtraTab> = when (kind) {
    "ResourceQuota" -> listOf(resourceQuotaUsageTab(res, client))
    "Role", "ClusterRole" -> listOf(policyRulesTab(res, client, kind))
    "RoleBinding", "ClusterRoleBinding" -> listOf(roleBindingTab(res, client, kind))
    "EndpointSlice" -> listOf(endpointSliceTab(res, client, onNavigate))
    else -> emptyList()
}

/**
 * Returns kind-specific sections appended to the Overview tab of
 * [ResourceDetailPanel] — the Node panel's inline-pod-list shape, for kinds
 * whose pods are the first thing worth seeing. Same extension point contract
 * as [kindExtraTabs].
 */
fun kindOverviewSections(
    kind: String,
    res: GenericResourceInfo,
    client: ReactiveKubeClient,
    onNavigate: (Screen) -> Unit = {},
): List<OverviewSection> = when (kind) {
    "SparkApplication" ->
        res.namespace?.let { ns ->
            listOf(podsOverviewSection(res, onNavigate) { client.listSparkApplicationPods(ns, res.uid, res.name) })
        } ?: emptyList()

    "Job" ->
        res.namespace?.let { ns ->
            listOf(podsOverviewSection(res, onNavigate) { client.listJobPods(ns, res.uid) })
        } ?: emptyList()

    "CronJob" ->
        res.namespace?.let { ns ->
            listOf(podsOverviewSection(res, onNavigate) { client.listCronJobPods(ns, res.uid) })
        } ?: emptyList()

    else -> emptyList()
}

private fun resourceQuotaUsageTab(
    res: GenericResourceInfo,
    client: ReactiveKubeClient,
): ExtraTab = ExtraTab(
    label = "Usage",
    icon = Res.drawable.monitor_heart_filled,
) {
    // Fetch lazily, inside the content lambda, so the GET only fires when the
    // user actually opens the Usage tab (mirrors DeploymentResourceGraphTab).
    var rows by remember(res.uid) { mutableStateOf<List<QuotaUsageRow>?>(null) }

    LaunchedEffect(res.uid) {
        rows = null
        rows =
            withContext(Dispatchers.IO) {
                client.getResourceQuotaUsage(res.name, res.namespace ?: "")
            }
    }

    when {
        rows == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
        }

        rows!!.isEmpty() -> {
            EmptyState(
                icon = Res.drawable.monitor_heart_filled,
                kind = "No quota usage data",
            )
        }

        else -> {
            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Resource Usage",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                )
                Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                    Column(
                        modifier =
                        Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rows!!.forEach { row -> UsageBarRow(row) }
                    }
                }
            }
        }
    }
}

private fun policyRulesTab(
    res: GenericResourceInfo,
    client: ReactiveKubeClient,
    kind: String,
): ExtraTab = ExtraTab(
    label = "Rules",
    icon = Res.drawable.security_filled,
) {
    var rules by remember(res.uid) { mutableStateOf<List<PolicyRuleRow>?>(null) }

    LaunchedEffect(res.uid) {
        rules = null
        rules =
            withContext(Dispatchers.IO) {
                client.getPolicyRules(kind, res.name, res.namespace)
            }
    }

    when {
        rules == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
        }

        rules!!.isEmpty() -> {
            EmptyState(
                icon = Res.drawable.security_filled,
                kind = "No rules defined",
            )
        }

        else -> {
            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Policy Rules",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                )
                rules!!.forEachIndexed { idx, rule ->
                    PolicyRuleCard(rule = rule, index = idx + 1)
                }
            }
        }
    }
}

private fun roleBindingTab(
    res: GenericResourceInfo,
    client: ReactiveKubeClient,
    kind: String,
): ExtraTab = ExtraTab(
    label = "Bindings",
    icon = Res.drawable.account_tree_filled,
) {
    var detail by remember(res.uid) { mutableStateOf<RoleBindingDetail?>(null) }
    var loaded by remember(res.uid) { mutableStateOf(false) }

    LaunchedEffect(res.uid) {
        detail = null
        loaded = false
        detail =
            withContext(Dispatchers.IO) {
                client.getRoleBindingDetail(kind, res.name, res.namespace)
            }
        loaded = true
    }

    when {
        !loaded -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
        }

        detail == null -> {
            EmptyState(
                icon = Res.drawable.account_tree_filled,
                kind = "Binding not found",
            )
        }

        else -> {
            val d = detail!!
            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Role reference section
                Text(
                    "Role Reference",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                )
                Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                    Column(
                        modifier =
                        Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                    ) {
                        LabeledLine(label = "Kind / Name", value = "${d.roleRefKind}/${d.roleRefName}")
                    }
                }

                // Subjects section
                if (d.subjects.isNotEmpty()) {
                    Text(
                        "Subjects",
                        style = MaterialTheme.typography.labelLarge,
                        color = KdTextPrimary,
                    )
                    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                        Column(
                            modifier =
                            Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            d.subjects.forEachIndexed { idx, subj ->
                                if (idx > 0) HorizontalDivider(color = KdTextSecondary.copy(alpha = 0.15f))
                                LabeledLine(label = "Kind", value = subj.kind)
                                LabeledLine(label = "Name", value = subj.name)
                                if (!subj.namespace.isNullOrBlank()) {
                                    LabeledLine(label = "Namespace", value = subj.namespace)
                                }
                            }
                        }
                    }
                }

                // Resolved permissions section
                Text(
                    "Resolved Permissions",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                )
                if (!d.resolvedRoleFound) {
                    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                        Box(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                            Text(
                                "Referenced role not found",
                                style = MaterialTheme.typography.bodySmall,
                                color = KdError,
                            )
                        }
                    }
                } else if (d.resolvedRules.isEmpty()) {
                    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                        Box(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                            Text(
                                "Role exists but has no rules",
                                style = MaterialTheme.typography.bodySmall,
                                color = KdTextSecondary,
                            )
                        }
                    }
                } else {
                    d.resolvedRules.forEachIndexed { idx, rule ->
                        PolicyRuleCard(rule = rule, index = idx + 1)
                    }
                }
            }
        }
    }
}

private fun endpointSliceTab(
    res: GenericResourceInfo,
    client: ReactiveKubeClient,
    onNavigate: (Screen) -> Unit,
): ExtraTab = ExtraTab(
    label = "Endpoints",
    icon = Res.drawable.settings_ethernet_filled,
) {
    var detail by remember(res.uid) { mutableStateOf<EndpointSliceDetail?>(null) }
    var loaded by remember(res.uid) { mutableStateOf(false) }

    LaunchedEffect(res.uid) {
        detail = null
        loaded = false
        detail =
            withContext(Dispatchers.IO) {
                client.getEndpointSliceDetail(res.name, res.namespace)
            }
        loaded = true
    }

    // Read the services flow inside the composable so navigation can resolve the
    // backing Service by name + namespace at click-time without a blocking fetch.
    val servicesState by client.services.collectAsState()

    when {
        !loaded -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
        }

        detail == null -> {
            EmptyState(
                icon = Res.drawable.settings_ethernet_filled,
                kind = "EndpointSlice not found",
            )
        }

        else -> {
            val d = detail!!
            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ── Summary section ─────────────────────────────────────────
                Text(
                    "Summary",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                )
                Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                    Column(
                        modifier =
                        Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LabeledLine(label = "Address Type", value = d.addressType)

                        // Backing Service link — only rendered when the label is present
                        if (d.serviceName != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Backing Service:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KdTextSecondary,
                                )
                                Text(
                                    d.serviceName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KdSuccess,
                                    modifier =
                                    Modifier.clickable {
                                        val found =
                                            (servicesState as? ResourceState.Success)?.data
                                                ?.firstOrNull {
                                                    it.name == d.serviceName &&
                                                        it.namespace == res.namespace
                                                }
                                        if (found != null) {
                                            onNavigate(Screen.Detail.ServiceDetail(found))
                                        } else {
                                            onNavigate(Screen.Main.Services)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // ── Endpoints section ───────────────────────────────────────
                if (d.endpoints.isNotEmpty()) {
                    Text(
                        "Endpoints",
                        style = MaterialTheme.typography.labelLarge,
                        color = KdTextPrimary,
                    )
                    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                        Column(
                            modifier =
                            Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            d.endpoints.forEachIndexed { idx, ep ->
                                if (idx > 0) HorizontalDivider(color = KdTextSecondary.copy(alpha = 0.15f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        ep.addresses.ifBlank { "—" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = KdTextPrimary,
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (ep.ready) KdSuccess.copy(alpha = 0.15f) else KdError.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            if (ep.ready) "Ready" else "NotReady",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (ep.ready) KdSuccess else KdError,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (!ep.hostname.isNullOrBlank()) {
                                    LabeledLine(label = "Hostname", value = ep.hostname)
                                }
                                if (!ep.nodeName.isNullOrBlank()) {
                                    LabeledLine(label = "Node", value = ep.nodeName)
                                }
                            }
                        }
                    }
                }

                // ── Ports section ───────────────────────────────────────────
                if (d.ports.isNotEmpty()) {
                    Text(
                        "Ports",
                        style = MaterialTheme.typography.labelLarge,
                        color = KdTextPrimary,
                    )
                    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                        Column(
                            modifier =
                            Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            d.ports.forEachIndexed { idx, p ->
                                if (idx > 0) HorizontalDivider(color = KdTextSecondary.copy(alpha = 0.15f))
                                LabeledLine(label = "Name", value = p.name)
                                LabeledLine(label = "Port", value = p.port)
                                LabeledLine(label = "Protocol", value = p.protocol)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun podsOverviewSection(
    res: GenericResourceInfo,
    onNavigate: (Screen) -> Unit,
    fetch: suspend () -> Result<List<PodInfo>>,
): OverviewSection = OverviewSection(key = "pods") {
    // Auto-refresh while the section is visible: pods churn exactly when this
    // panel is open (executors scaling, a CronJob firing). The loop dies with
    // the composition, so nothing polls once the panel closes or the user
    // switches resource or tab.
    var pods by remember(res.uid) { mutableStateOf<Result<List<PodInfo>>?>(null) }

    LaunchedEffect(res.uid) {
        pods = null
        while (true) {
            val next = fetch()
            // Keep the last good list through a transient refresh failure —
            // flipping a visible list to an error card on a blip helps no one.
            if (next.isSuccess || pods?.isSuccess != true) pods = next
            delay(10_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Pods",
                style = MaterialTheme.typography.labelLarge,
                color = KdTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            val count = pods?.getOrNull()?.size
            if (count != null) {
                Text("$count", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
            }
        }

        val snapshot = pods
        when {
            snapshot == null -> {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = KdPrimary)
                }
            }

            snapshot.isFailure -> {
                Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                    Box(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                        Text(
                            "Could not load pods",
                            style = MaterialTheme.typography.bodySmall,
                            color = KdError,
                        )
                    }
                }
            }

            snapshot.getOrThrow().isEmpty() -> {
                Text("No pods", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
            }

            else -> {
                snapshot.getOrThrow().forEach { pod ->
                    OwnedPodItem(pod) { onNavigate(Screen.Main.Pods(selectPodUid = pod.uid)) }
                }
            }
        }
    }
}

// ── Shared composables ──────────────────────────────────────────────────────

/** A card showing one [PolicyRuleRow] with labeled fields (empty fields hidden). */
@Composable
private fun PolicyRuleCard(
    rule: PolicyRuleRow,
    index: Int,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
        Column(
            modifier =
            Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Rule $index",
                style = MaterialTheme.typography.labelMedium,
                color = KdTextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            LabeledLine(label = "Verbs", value = rule.verbs)
            LabeledLine(label = "Resources", value = rule.resources)
            LabeledLine(label = "API Groups", value = rule.apiGroups)
            if (rule.resourceNames.isNotBlank()) LabeledLine(label = "Resource Names", value = rule.resourceNames)
            if (rule.nonResourceUrls.isNotBlank()) LabeledLine(label = "Non-Resource URLs", value = rule.nonResourceUrls)
        }
    }
}

/** One label+value row; hidden when [value] is blank. */
@Composable
private fun LabeledLine(
    label: String,
    value: String,
) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = KdTextSecondary,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
        )
    }
}

/**
 * One pod row in an owned-pods tab (SparkApplication / Job / CronJob); all
 * rows share the owner's namespace. The role chip renders only for pods that
 * carry a `spark-role` label.
 */
@Composable
private fun OwnedPodItem(pod: PodInfo, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = KdSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pod.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = KdPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(pod.status)
                    val role = pod.labels["spark-role"]
                    if (role != null) {
                        Surface(shape = RoundedCornerShape(4.dp), color = KdTextSecondary.copy(alpha = 0.15f)) {
                            Text(
                                role,
                                style = MaterialTheme.typography.labelSmall,
                                color = KdTextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Ready ${pod.ready}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                )
                Text(
                    pod.age,
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                )
            }
        }
    }
}

@Composable
fun UsageBarRow(row: QuotaUsageRow) {
    val barColor =
        when {
            row.fraction < 0.7f -> KdSuccess
            row.fraction < 0.85f -> KdWarning
            else -> KdError
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.resource,
                style = MaterialTheme.typography.bodySmall,
                color = KdTextSecondary,
            )
            Text(
                "${row.used} / ${row.hard}",
                style = MaterialTheme.typography.bodySmall,
                color = KdTextPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { row.fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = barColor,
            trackColor = KdSurfaceVariant,
        )
    }
}
