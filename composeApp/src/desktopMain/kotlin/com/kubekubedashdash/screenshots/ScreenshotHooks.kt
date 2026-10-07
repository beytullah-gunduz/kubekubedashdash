package com.kubekubedashdash.screenshots

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Global, screenshot-only coordination signals read by GenericResourceScreen and
 * ResourceDetailPanel. ALWAYS empty in normal use — only GenerateScreenshots
 * writes to these maps, so production rendering is unaffected (empty map ⇒ the
 * keyed lookups below all return null and no extra effect runs).
 *
 * Keyed by Kubernetes Kind string (e.g. "ResourceQuota", "CronJob",
 * "CertificateSigningRequest"). For autoSelect the value is a resource NAME; for
 * autoTab the value is an extra-tab LABEL (e.g. "Usage", "Rules", "Bindings",
 * "Endpoints").
 */
object ScreenshotHooks {
    val autoSelect = MutableStateFlow<Map<String, String>>(emptyMap())
    val autoTab = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Screenshot-only: the detail host ignores the per-kind width memory, so captures never depend on the developer's preferences. */
    val ignorePaneWidthMemory = MutableStateFlow(false)

    /** Screenshot-only: the command palette opens pre-filled with this query. Empty in normal use. */
    val paletteQuery = MutableStateFlow("")

    /** Screenshot-only: the Pods list selects its first N visible rows. 0 in normal use. */
    val autoSelectPodCount = MutableStateFlow(0)

    /** Screenshot-only: slows the Retro power-on so a frame sequence can be captured. 1 in normal use. */
    val crtTimeScale = MutableStateFlow(1)

    /**
     * Screenshot-only: the topology graph expands the pod groups whose "<Kind>/<name>" key is in this
     * set (Kind = the card's kind label, e.g. "Deployment/frontend"). Empty in normal use.
     */
    val topologyExpand = MutableStateFlow<Set<String>>(emptySet())

    /** Screenshot-only: the topology graph selects the node with this "<Kind>/<name>" key, e.g. "Service/frontend-svc". Empty in normal use. */
    val topologySelect = MutableStateFlow("")
}
