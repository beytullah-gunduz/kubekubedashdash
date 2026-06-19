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

    fun isMockContext(ctx: String): Boolean = ctx == MOCK_CONTEXT_NAME || ctx.startsWith("$MOCK_LABEL_PREFIX #")
}
