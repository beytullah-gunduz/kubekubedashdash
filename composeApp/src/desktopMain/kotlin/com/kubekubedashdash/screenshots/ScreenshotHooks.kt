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
}
