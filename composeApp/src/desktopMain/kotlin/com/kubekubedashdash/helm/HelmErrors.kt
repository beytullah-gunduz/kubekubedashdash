package com.kubekubedashdash.helm

/** True when a list/watch failure is an RBAC refusal. Matches fabric8's KubernetesClientException text. */
fun isHelmForbidden(message: String?): Boolean = message != null && ("code=403" in message || "reason=Forbidden" in message || " is forbidden" in message)

const val HELM_FORBIDDEN_MESSAGE =
    "Listing Helm releases needs permission to list and watch Secrets labelled owner=helm " +
        "in the selected namespaces, and your credentials were refused (HTTP 403). " +
        "If your access is limited to some namespaces, select one of them in the namespace selector."

const val HELM_CONFIGMAP_FORBIDDEN_WARNING =
    "Releases stored in ConfigMaps (HELM_DRIVER=configmap) are not shown: listing ConfigMaps was refused (HTTP 403)."
