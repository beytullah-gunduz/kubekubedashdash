package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app caps fabric8's per-request retry budget, but only where fabric8's
 * own default is in force: a limit the user set through fabric8's property
 * or environment variable is theirs. Pure; no client is built.
 */
class RequestRetryBudgetTest {

    @Test
    fun `fabric8's default budget is replaced by the app's cap`() {
        val config = Config.empty()
        assertEquals(Config.DEFAULT_REQUEST_RETRY_BACKOFFLIMIT, config.requestRetryBackoffLimit, "precondition: fabric8's default")

        config.withBoundedRetries()

        assertEquals(REQUEST_RETRY_BACKOFF_LIMIT, config.requestRetryBackoffLimit)
        assertEquals(Config.DEFAULT_REQUEST_RETRY_BACKOFFINTERVAL, config.requestRetryBackoffInterval, "the interval is not the app's to change")
    }

    @Test
    fun `an explicit budget is respected, larger or smaller`() {
        val none = Config.empty().apply { requestRetryBackoffLimit = 0 }
        val generous = Config.empty().apply { requestRetryBackoffLimit = 25 }

        none.withBoundedRetries()
        generous.withBoundedRetries()

        assertEquals(0, none.requestRetryBackoffLimit)
        assertEquals(25, generous.requestRetryBackoffLimit)
    }

    @Test
    fun `the cap is idempotent and returns the same config`() {
        val config = Config.empty()

        val same = config.withBoundedRetries().withBoundedRetries()

        assertEquals(REQUEST_RETRY_BACKOFF_LIMIT, same.requestRetryBackoffLimit)
        assertEquals(config, same)
    }
}
