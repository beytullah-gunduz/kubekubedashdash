package com.kubekubedashdash.ui

import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.cloud_circle_filled
import com.kubekubedashdash.resources.database_filled
import com.kubekubedashdash.resources.extension_filled
import com.kubekubedashdash.resources.flowchart_filled
import com.kubekubedashdash.resources.gavel_filled
import com.kubekubedashdash.resources.inventory_2_filled
import com.kubekubedashdash.resources.key_filled
import com.kubekubedashdash.resources.lan_filled
import com.kubekubedashdash.resources.license_filled
import com.kubekubedashdash.resources.memory_filled
import com.kubekubedashdash.resources.monitoring_filled
import com.kubekubedashdash.resources.swap_horiz_filled
import org.jetbrains.compose.resources.DrawableResource

/** One icon and the API groups that take it. Each group also covers its subdomains. */
internal data class CrdIconRule(val icon: DrawableResource, val groups: List<String>)

/**
 * Icons for well-known CRD API groups, chosen by what the group does. CRDs carry no icon of their own,
 * and one shared puzzle piece gave a long Custom Resources list nothing to scan by; with one icon per
 * group, a change of icon marks where the next group starts. Groups are lowercase, without a leading dot.
 *
 * The icons avoid glyphs that already stand for a built-in kind (lock, account_tree, security, cloud),
 * except autoscaling, which shares the HorizontalPodAutoscaler's icon because those CRDs are autoscalers.
 */
internal val CrdIconRules: List<CrdIconRule> = listOf(
    // Certificates
    CrdIconRule(Res.drawable.license_filled, listOf("cert-manager.io")),
    // Secrets
    CrdIconRule(
        Res.drawable.key_filled,
        listOf("external-secrets.io", "secrets-store.csi.x-k8s.io", "bitnami.com", "secrets.hashicorp.com"),
    ),
    // Workflows and delivery
    CrdIconRule(Res.drawable.flowchart_filled, listOf("argoproj.io", "tekton.dev", "fluxcd.io", "flagger.app")),
    // Networking and service mesh
    CrdIconRule(
        Res.drawable.lan_filled,
        listOf(
            "cilium.io", "projectcalico.org", "istio.io", "linkerd.io", "gateway.networking.k8s.io",
            "gateway.networking.x-k8s.io", "traefik.io", "traefik.containo.us", "projectcontour.io", "metallb.io",
            "networking.gke.io",
        ),
    ),
    // Monitoring and observability
    CrdIconRule(
        Res.drawable.monitoring_filled,
        listOf(
            "monitoring.coreos.com",
            "monitoring.googleapis.com",
            "opentelemetry.io",
            "grafana.integreatly.org",
            "jaegertracing.io",
            "logging.banzaicloud.io",
        ),
    ),
    // Autoscaling
    CrdIconRule(
        Res.drawable.swap_horiz_filled,
        listOf("autoscaling.k8s.io", "autoscaling.x-k8s.io", "autoscaling.gke.io", "keda.sh", "karpenter.sh", "karpenter.k8s.aws"),
    ),
    // Policy
    CrdIconRule(Res.drawable.gavel_filled, listOf("kyverno.io", "wgpolicyk8s.io", "gatekeeper.sh")),
    // Databases and messaging
    CrdIconRule(
        Res.drawable.database_filled,
        listOf("cnpg.io", "acid.zalan.do", "strimzi.io", "mongodb.com", "rabbitmq.com", "percona.com"),
    ),
    // Batch, data and ML compute
    CrdIconRule(
        Res.drawable.memory_filled,
        listOf("sparkoperator.k8s.io", "kubeflow.org", "ray.io", "volcano.sh", "kueue.x-k8s.io"),
    ),
    // Backup and storage
    CrdIconRule(
        Res.drawable.inventory_2_filled,
        listOf("velero.io", "snapshot.storage.k8s.io", "longhorn.io", "rook.io", "openebs.io", "objectbucket.io"),
    ),
    // Cloud providers and infrastructure
    CrdIconRule(
        Res.drawable.cloud_circle_filled,
        listOf("cloud.google.com", "gke.io", "k8s.aws", "azure.com", "crossplane.io", "upbound.io"),
    ),
)

/**
 * The icon for a custom resource of API [group]: the rule whose group is [group] itself or a parent domain of
 * it (`cert-manager.io` covers `acme.cert-manager.io`, never `notcert-manager.io`), ignoring case. When several
 * match, the longest wins whatever the table order, so `networking.gke.io` beats `gke.io`. Unknown groups keep
 * the puzzle piece.
 */
internal fun crdGroupIcon(group: String): DrawableResource {
    val g = group.lowercase()
    var best: DrawableResource? = null
    var bestLength = -1
    for (rule in CrdIconRules) {
        for (suffix in rule.groups) {
            if ((g == suffix || g.endsWith(".$suffix")) && suffix.length > bestLength) {
                best = rule.icon
                bestLength = suffix.length
            }
        }
    }
    return best ?: Res.drawable.extension_filled
}
