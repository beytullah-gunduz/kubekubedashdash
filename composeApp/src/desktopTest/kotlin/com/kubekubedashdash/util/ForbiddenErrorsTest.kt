package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForbiddenErrorsTest {

    @Test
    fun `fabric8's refusal text reads as forbidden`() {
        assertTrue(isForbidden("... Received status: Status(code=403, reason=Forbidden)"))
        assertTrue(isForbidden("secrets is forbidden: User cannot list"))
        assertTrue(isForbidden("reason=Forbidden"))
    }

    @Test
    fun `other failures and no message do not`() {
        assertFalse(isForbidden("Failure executing: GET ... timed out"))
        assertFalse(isForbidden("Received status: Status(code=401, reason=Unauthorized)"))
        assertFalse(isForbidden(null))
    }

    @Test
    fun `a 403 status code is forbidden whatever the message says, also as a cause`() {
        val refusal = KubernetesClientException(StatusBuilder().withCode(403).withMessage("denied").build())
        assertTrue(isForbidden(refusal))
        assertTrue(isForbidden(IllegalStateException("informer failed to start", refusal)))
    }

    @Test
    fun `an exception with no 403 anywhere is not`() {
        val serverError = KubernetesClientException(StatusBuilder().withCode(500).withMessage("etcdserver: request timed out").build())
        assertFalse(isForbidden(serverError))
        assertFalse(isForbidden(IllegalStateException("Not connected to a cluster")))
    }
}
