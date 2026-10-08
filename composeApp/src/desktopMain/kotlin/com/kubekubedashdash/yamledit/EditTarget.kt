package com.kubekubedashdash.yamledit

import com.kubekubedashdash.util.defaultPluralForKind

/** One live object the editor reads and replaces. [group] is "" for the core API group. */
data class EditTarget(
    /** As Kubernetes spells it: "ConfigMap", "Endpoints", "SparkApplication". */
    val kind: String,
    val group: String,
    val version: String,
    val plural: String,
    val namespaced: Boolean,
    val name: String,
    /** Non-null iff [namespaced]. */
    val namespace: String?,
) {
    init {
        require(namespaced == (namespace != null)) { "namespace must be set iff the kind is namespaced" }
        require(SAFE_SEGMENT.matches(name)) { "unsupported name" }
        namespace?.let { require(SAFE_SEGMENT.matches(it)) { "unsupported namespace" } }
    }

    val apiVersion: String get() = if (group.isEmpty()) version else "$group/$version"

    /** Registry identity within one cluster tab. */
    val key: String get() = "$group/$version/$plural/${namespace.orEmpty()}/$name"

    /** `namespace/name`, or `name` for a cluster-scoped object. */
    val ref: String get() = if (namespace == null) name else "$namespace/$name"

    /** REST path of the object, e.g. `/api/v1/namespaces/default/configmaps/app-config`. */
    fun path(): String = collectionPath() + "/" + name

    /** REST path of the collection, e.g. `/apis/apps/v1/namespaces/default/deployments`. */
    fun collectionPath(): String = buildString {
        append(if (group.isEmpty()) "/api/$version" else "/apis/$group/$version")
        if (namespace != null) append("/namespaces/").append(namespace)
        append('/').append(plural)
    }

    companion object {
        /** Kubernetes names never contain these; refusing them keeps the path a plain path. */
        private val SAFE_SEGMENT = Regex("""[^/?#%\s]+""")
    }
}

/** A built-in kind's REST shape. [group] is "" for the core API group. */
internal data class BuiltInKind(
    val kind: String,
    val group: String,
    val version: String,
    val plural: String,
    val namespaced: Boolean,
)

/** Which kinds the editor can replace, and where each one lives. */
internal object EditableKinds {
    /** Every built-in kind kkdd lists, at the API version getResourceYaml reads it with. */
    val BUILT_INS: List<BuiltInKind> = listOf(
        BuiltInKind("Pod", "", "v1", "pods", true),
        BuiltInKind("Service", "", "v1", "services", true),
        BuiltInKind("Node", "", "v1", "nodes", false),
        BuiltInKind("Namespace", "", "v1", "namespaces", false),
        BuiltInKind("ConfigMap", "", "v1", "configmaps", true),
        BuiltInKind("Secret", "", "v1", "secrets", true),
        BuiltInKind("PersistentVolume", "", "v1", "persistentvolumes", false),
        BuiltInKind("PersistentVolumeClaim", "", "v1", "persistentvolumeclaims", true),
        BuiltInKind("ServiceAccount", "", "v1", "serviceaccounts", true),
        BuiltInKind("ResourceQuota", "", "v1", "resourcequotas", true),
        BuiltInKind("LimitRange", "", "v1", "limitranges", true),
        BuiltInKind("Endpoints", "", "v1", "endpoints", true),
        BuiltInKind("Deployment", "apps", "v1", "deployments", true),
        BuiltInKind("StatefulSet", "apps", "v1", "statefulsets", true),
        BuiltInKind("DaemonSet", "apps", "v1", "daemonsets", true),
        BuiltInKind("ReplicaSet", "apps", "v1", "replicasets", true),
        BuiltInKind("Job", "batch", "v1", "jobs", true),
        BuiltInKind("CronJob", "batch", "v1", "cronjobs", true),
        BuiltInKind("Ingress", "networking.k8s.io", "v1", "ingresses", true),
        BuiltInKind("IngressClass", "networking.k8s.io", "v1", "ingressclasses", false),
        BuiltInKind("NetworkPolicy", "networking.k8s.io", "v1", "networkpolicies", true),
        BuiltInKind("StorageClass", "storage.k8s.io", "v1", "storageclasses", false),
        BuiltInKind("CSIDriver", "storage.k8s.io", "v1", "csidrivers", false),
        BuiltInKind("Role", "rbac.authorization.k8s.io", "v1", "roles", true),
        BuiltInKind("ClusterRole", "rbac.authorization.k8s.io", "v1", "clusterroles", false),
        BuiltInKind("RoleBinding", "rbac.authorization.k8s.io", "v1", "rolebindings", true),
        BuiltInKind("ClusterRoleBinding", "rbac.authorization.k8s.io", "v1", "clusterrolebindings", false),
        BuiltInKind("HorizontalPodAutoscaler", "autoscaling", "v2", "horizontalpodautoscalers", true),
        BuiltInKind("PodDisruptionBudget", "policy", "v1", "poddisruptionbudgets", true),
        BuiltInKind("PriorityClass", "scheduling.k8s.io", "v1", "priorityclasses", false),
        BuiltInKind("ValidatingWebhookConfiguration", "admissionregistration.k8s.io", "v1", "validatingwebhookconfigurations", false),
        BuiltInKind("MutatingWebhookConfiguration", "admissionregistration.k8s.io", "v1", "mutatingwebhookconfigurations", false),
        BuiltInKind("EndpointSlice", "discovery.k8s.io", "v1", "endpointslices", true),
        BuiltInKind("CertificateSigningRequest", "certificates.k8s.io", "v1", "certificatesigningrequests", false),
        // Serves the Apply window's kind resolver; no CRD object has a YAML tab, so the editor never reaches it.
        BuiltInKind("CustomResourceDefinition", "apiextensions.k8s.io", "v1", "customresourcedefinitions", false),
    )

    /** Router labels that differ from the API kind. */
    private val ALIASES = mapOf("endpoint" to "endpoints")

    /**
     * The target for a YAML tab's arguments, or null when it can't be edited: a kind that is not a
     * built-in and carries no API group (Event, anything unknown), a built-in whose scope disagrees
     * with [namespace], or a custom resource without a [version].
     */
    fun targetFor(kind: String, name: String, namespace: String?, group: String?, version: String?, plural: String?): EditTarget? = try {
        if (group.isNullOrBlank()) {
            val lower = kind.lowercase()
            val wanted = ALIASES[lower] ?: lower
            val builtIn = BUILT_INS.firstOrNull { it.kind.lowercase() == wanted }
            if (builtIn == null || builtIn.namespaced != (namespace != null)) {
                null
            } else {
                EditTarget(builtIn.kind, builtIn.group, builtIn.version, builtIn.plural, builtIn.namespaced, name, namespace)
            }
        } else if (version.isNullOrBlank()) {
            null
        } else {
            // Same rule as ReactiveKubeClient's generic branch: an instance carries a namespace iff its CRD is namespaced.
            val effectivePlural = plural?.takeIf { it.isNotBlank() } ?: defaultPluralForKind(kind)
            EditTarget(kind, group, version, effectivePlural, namespace != null, name, namespace)
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Built-in by (group, kind) for the Apply window; any version is accepted (the document's own version is used). */
    fun builtIn(group: String, kind: String): BuiltInKind? = BUILT_INS.firstOrNull { it.group == group && it.kind == kind }
}
