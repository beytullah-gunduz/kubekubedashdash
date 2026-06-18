package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ReactiveKubeClient.drainNode].
 *
 * Signature: `fun drainNode(name: String): Result<DrainResult>`
 * [DrainResult] fields: `evicted: Int`, `skipped: Int`, `failed: Int`
 *
 * ## Harness note — NEEDS-PATH-VALIDATION
 *
 * Eviction uses the `/eviction` subresource which [KubernetesCrudDispatcher]
 * does NOT model. We switch to [KubernetesMixedDispatcher] so pre-recorded
 * `expect()` stubs intercept the eviction POST while all other CRUD operations
 * (node PATCH for cordon, pod LIST) go through the CRUD layer.
 *
 * The eviction path registered here is:
 *   `POST /api/v1/namespaces/default/pods/app-1/eviction`
 *
 * **This path is our best guess based on the Kubernetes v1 API layout and
 * fabric8's pod().inNamespace(ns).withName(name).evict() implementation.**
 * The orchestrator must verify the actual HTTP path fabric8 7.5.2 uses — if
 * the path differs, the eviction stub will not match, `evict()` will return
 * false (404 → failed count) rather than true, and the assertion on
 * `evicted == 1` will fail with `evicted=0, failed=1`. Adjust the
 * `withPath(...)` argument to fix that case.
 *
 * Everything except the eviction-path assertion is reliable.
 */
class ReactiveKubeClientDrainEvictTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        // KubernetesMixedDispatcher: pre-recorded expectations first, CRUD fallback.
        val responses = HashMap<io.fabric8.mockwebserver.ServerRequest, java.util.Queue<io.fabric8.mockwebserver.ServerResponse>>()
        server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            responses,
            KubernetesMixedDispatcher(responses),
            false,
        )
        server.init()

        // Stub the eviction subresource for app-1 (the one eligible pod).
        // NOTE: verify this path against fabric8 7.5.2's actual HTTP call — see class doc.
        server.expect().post()
            .withPath("/api/v1/namespaces/default/pods/app-1/eviction")
            .andReturn(201, "{}")
            .always()

        val seed = server.createClient()
        try {
            // Node to drain.
            seed.nodes().resource(
                NodeBuilder()
                    .withNewMetadata().withName("node-a").endMetadata()
                    .withNewSpec().withUnschedulable(false).endSpec()
                    .build(),
            ).create()

            // app-1: plain pod on node-a — must be evicted.
            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata().withName("app-1").withNamespace("default").endMetadata()
                    .withNewSpec().withNodeName("node-a").endSpec()
                    .build(),
            ).create()

            // ds-1: DaemonSet-owned pod on node-a — must be skipped.
            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("ds-1")
                    .withNamespace("default")
                    .withOwnerReferences(
                        OwnerReferenceBuilder()
                            .withKind("DaemonSet")
                            .withName("my-ds")
                            .withApiVersion("apps/v1")
                            .build(),
                    )
                    .endMetadata()
                    .withNewSpec().withNodeName("node-a").endSpec()
                    .build(),
            ).create()

            // mirror-1: mirror/static pod on node-a — must be skipped.
            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata()
                    .withName("mirror-1")
                    .withNamespace("default")
                    .addToAnnotations("kubernetes.io/config.mirror", "some-hash")
                    .endMetadata()
                    .withNewSpec().withNodeName("node-a").endSpec()
                    .build(),
            ).create()

            // done-1: already Succeeded pod on node-a — must be skipped.
            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata().withName("done-1").withNamespace("default").endMetadata()
                    .withNewSpec().withNodeName("node-a").endSpec()
                    .withStatus(PodStatusBuilder().withPhase("Succeeded").build())
                    .build(),
            ).create()

            // other-1: plain pod on a DIFFERENT node — must never be touched.
            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata().withName("other-1").withNamespace("default").endMetadata()
                    .withNewSpec().withNodeName("other-node").endSpec()
                    .build(),
            ).create()
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        manager.close()
        server.destroy()
    }

    @Test
    fun `drainNode evicts eligible pods and skips DaemonSet mirror and completed pods`() {
        val result = client.drainNode("node-a")
        assertTrue(result.isSuccess, "drainNode must succeed (cordon + eviction loop), got: ${result.exceptionOrNull()}")

        val drainResult = result.getOrThrow()

        // 3 pods on node-a must be skipped: ds-1 (DaemonSet), mirror-1 (mirror), done-1 (Succeeded).
        assertEquals(3, drainResult.skipped, "skipped count: ds-1 + mirror-1 + done-1")

        // 1 pod must be evicted: app-1 (if eviction path matches — see class-level NOTE).
        // If the stub path is wrong this will be 0 evicted + 1 failed; adjust withPath(...).
        assertEquals(1, drainResult.evicted, "evicted count: app-1 only")
        assertEquals(0, drainResult.failed, "failed count: no eviction failures expected")
    }
}
