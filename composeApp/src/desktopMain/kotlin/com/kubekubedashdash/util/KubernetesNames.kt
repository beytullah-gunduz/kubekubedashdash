package com.kubekubedashdash.util

/** Name rules the Kubernetes API server applies, for values typed or read from a file before they reach a request. */
internal object KubernetesNames {
    private val DNS_LABEL = Regex("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")

    /** A DNS-1123 label: what a namespace name must be. */
    fun isDnsLabel(value: String): Boolean = DNS_LABEL.matches(value)
}
