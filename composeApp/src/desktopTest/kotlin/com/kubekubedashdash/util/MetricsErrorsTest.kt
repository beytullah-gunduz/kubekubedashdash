package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which failed metrics reads mean "no metrics here" and which are a blip the
 * polls ride out, and what of a failure reaches the log. The message below
 * stands in for fabric8's, which names the request URL.
 */
class MetricsErrorsTest {

    private val url = "Failure executing: GET at: https://cluster.invalid/apis/metrics.k8s.io/v1beta1/namespaces/ns-a/pods."

    private fun answered(code: Int) = KubernetesClientException(url, code, StatusBuilder().withCode(code).build())

    @Test
    fun `a missing, unreachable or refused metrics API is unavailable`() {
        for (code in listOf(403, 404, 503)) {
            assertTrue(isMetricsUnavailable(answered(code)), "HTTP $code")
            // fabric8 rethrows the failure as the cause of a copy.
            assertTrue(isMetricsUnavailable(KubernetesClientException("wrapped", answered(code))), "HTTP $code as a cause")
        }
    }

    @Test
    fun `a server error or a request that got no answer is transient`() {
        for (code in listOf(401, 429, 500, 504)) assertFalse(isMetricsUnavailable(answered(code)), "HTTP $code")
        assertFalse(isMetricsUnavailable(KubernetesClientException(url, SocketTimeoutException("timed out"))))
    }

    @Test
    fun `the logged shape is a status code or an exception class, never the message`() {
        assertEquals("HTTP 500", describeFailureShape(KubernetesClientException("wrapped", answered(500))))
        assertEquals("SocketTimeoutException", describeFailureShape(KubernetesClientException(url, SocketTimeoutException(url))))
    }
}
