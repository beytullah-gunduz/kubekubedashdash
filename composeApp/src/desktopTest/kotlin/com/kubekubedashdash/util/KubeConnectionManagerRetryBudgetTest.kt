package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * With fabric8's default budget a connect against a dead address retries ten
 * times behind a capped exponential back-off — about 19 s of waiting on a
 * refused port (measured in KubeConnectionManagerFailureTest), and about two
 * minutes against an address that drops packets. The app's cap keeps the
 * refused case around a second. The connect seam feeds a config whose retry
 * limit is still fabric8's default, run through the cap; a loopback port
 * nothing listens on; no kubeconfig, no real user state.
 */
class KubeConnectionManagerRetryBudgetTest {

    private var manager: KubeConnectionManager? = null

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "KubeConnectionManagerRetryBudgetTest", manager = manager)
    }

    @Test
    fun `a connect against a dead address fails inside the capped budget`() {
        val mgr = KubeConnectionManager(
            loadConfig = {
                Config.empty().apply {
                    masterUrl = "http://127.0.0.1:1"
                    requestTimeout = 2_000
                    // requestRetryBackoffLimit deliberately left at fabric8's default
                }.withBoundedRetries()
            },
        )
        manager = mgr

        val startedAt = System.nanoTime()
        val result = mgr.connect("dead")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(result.isFailure, "nothing listens on the port")
        assertTrue(elapsedMs < 10_000, "capped budget must fail fast; took $elapsedMs ms (about 19 s on fabric8's default)")
    }
}
