package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.Config

/**
 * App-wide cap on fabric8's per-request retry budget.
 *
 * fabric8 retries a request that failed with an IOException, a 429 or a 5xx
 * ten times by default, waiting 100 ms × 2^min(n, 5) between attempts —
 * about 19 s of back-off — with a 10 s request timeout on each, so one
 * blocking call against a dead cluster could park its thread for about two
 * minutes. The liveness probe and the MCP workers bound their own calls;
 * every other blocking call (the connect probe, YAML, delete, scale, logs)
 * ran on the full budget. Five retries keep a control-plane blip of a few
 * seconds covered (3.1 s of back-off over six attempts — and fabric8 never
 * retries a failed initial informer LIST beyond this budget, so it is also
 * an informer's tolerance at start) and bound the dead-cluster case at about
 * 3 s for a refused port and about a minute for one that drops packets. The
 * interval and the request timeout are fabric8's.
 */
internal const val REQUEST_RETRY_BACKOFF_LIMIT = 5

/**
 * Applies [REQUEST_RETRY_BACKOFF_LIMIT] where fabric8's own default is in
 * force. A limit the user set through fabric8's
 * `kubernetes.request.retry.backoffLimit` property or its
 * `KUBERNETES_REQUEST_RETRY_BACKOFFLIMIT` environment variable is theirs and
 * stays, larger or smaller — except a user who set fabric8's own default of
 * 10 by hand, which is indistinguishable from the default and gets the cap.
 * Mutates and returns [this].
 */
internal fun Config.withBoundedRetries(): Config = apply {
    if (requestRetryBackoffLimit == Config.DEFAULT_REQUEST_RETRY_BACKOFFLIMIT) {
        requestRetryBackoffLimit = REQUEST_RETRY_BACKOFF_LIMIT
    }
}
