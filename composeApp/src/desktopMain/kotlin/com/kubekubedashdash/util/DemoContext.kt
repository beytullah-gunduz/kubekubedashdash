package com.kubekubedashdash.util

/**
 * Neutral, dependency-free recognition of demo-cluster context names, so prod UI/VMs can
 * identify demo tabs without importing the mock engine (MockClusterProvider). The mock
 * *lifecycle* (server, refcounted handles, simulators) stays in MockClusterProvider — only
 * the string-level identity lives here.
 */
object DemoContext {
    const val MOCK_CONTEXT_NAME = "demo-cluster (mock)"
    private const val MOCK_LABEL_PREFIX = "demo-cluster (mock)"

    /**
     * Screenshot-only display labels for minted demo instances, in mint order. ALWAYS empty in
     * normal use — only the screenshot generator sets it, before the first demo tab opens. A
     * listed label is a demo context (routing, reattach, port forwards) with its own preference
     * row, so each renamed tab keeps its own colour.
     */
    @Volatile
    var screenshotLabels: List<String> = emptyList()

    fun isScreenshotLabel(ctx: String): Boolean = ctx in screenshotLabels

    fun isMockContext(ctx: String): Boolean = ctx == MOCK_CONTEXT_NAME || ctx.startsWith("$MOCK_LABEL_PREFIX #") || isScreenshotLabel(ctx)

    /**
     * The key per-cluster preferences are stored under: every minted
     * "demo-cluster (mock) #N" folds back to the one row the picker lists.
     * A screenshot label is its own key.
     */
    fun preferenceKey(ctx: String): String = if (isMockContext(ctx) && !isScreenshotLabel(ctx)) MOCK_CONTEXT_NAME else ctx
}
