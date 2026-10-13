package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobTemplateSpecBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Queue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ReactiveKubeClient.triggerCronJob].
 *
 * Signature: `fun triggerCronJob(name: String, namespace: String): Result<Unit>`
 *
 * These tests also act as a regression guard for the D1 fix: `triggerCronJob`
 * must copy `jobTemplate.metadata.labels` and `jobTemplate.metadata.annotations`
 * onto the created Job, then apply `cronjob.kubernetes.io/instantiate=manual`
 * last (so our key wins on a clash).
 *
 * The created Job is owned by the CronJob, as `kubectl create job --from=cronjob` does: a controller
 * ownerReference without `blockOwnerDeletion`. A CronJob without a uid cannot be referenced, so it fails
 * before any Job is created.
 *
 * The server mixes canned answers with the CRUD store, which stamps a uid on every create: a CronJob
 * without one can only be served as a canned answer.
 */
class ReactiveKubeClientTriggerCronJobTest {

    private lateinit var responses: MutableMap<ServerRequest, Queue<ServerResponse>>
    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        responses = HashMap()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()

        val seed = server.createClient()
        try {
            // Primary CronJob: has jobTemplate metadata with labels + annotations.
            seed.batch().v1().cronjobs().inNamespace("default").resource(
                CronJobBuilder()
                    .withNewMetadata().withName("nightly").withNamespace("default").endMetadata()
                    .withNewSpec()
                    .withJobTemplate(
                        JobTemplateSpecBuilder()
                            .withNewMetadata()
                            .addToLabels("app", "backup")
                            .addToAnnotations("team", "infra")
                            .endMetadata()
                            .withNewSpec()
                            .withNewTemplate()
                            .withNewSpec()
                            .addNewContainer()
                            .withName("worker")
                            .withImage("busybox")
                            .endContainer()
                            .withRestartPolicy("Never")
                            .endSpec()
                            .endTemplate()
                            .endSpec()
                            .build(),
                    )
                    .endSpec()
                    .build(),
            ).create()

            // CronJob with a 60-character name (used for the 63-char truncation test).
            val longName = "a".repeat(60)
            seed.batch().v1().cronjobs().inNamespace("default").resource(
                CronJobBuilder()
                    .withNewMetadata().withName(longName).withNamespace("default").endMetadata()
                    .withNewSpec()
                    .withJobTemplate(
                        JobTemplateSpecBuilder()
                            .withNewSpec()
                            .withNewTemplate()
                            .withNewSpec()
                            .addNewContainer()
                            .withName("worker")
                            .withImage("busybox")
                            .endContainer()
                            .withRestartPolicy("Never")
                            .endSpec()
                            .endTemplate()
                            .endSpec()
                            .build(),
                    )
                    .endSpec()
                    .build(),
            ).create()
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        // No request retries: fabric8 retries a 5xx for ~100 s by default.
        val config = server.createClient().use { ConfigBuilder(it.configuration).withRequestRetryBackoffLimit(0).build() }
        manager.connectWithClient(KubernetesClientBuilder().withConfig(config).build(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientTriggerCronJobTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `triggerCronJob creates a Job with name prefixed by cronjob name`() {
        val result = client.actions.triggerCronJob("nightly", "default")
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val jobs = verify.batch().v1().jobs().inNamespace("default").list().items
            val created = jobs.firstOrNull { it.metadata?.name?.startsWith("nightly-manual-") == true }
            assertNotNull(created, "expected a Job whose name starts with 'nightly-manual-'")
        } finally {
            verify.close()
        }
    }

    @Test
    fun `triggerCronJob copies jobTemplate labels and annotations onto the created Job (D1)`() {
        val result = client.actions.triggerCronJob("nightly", "default")
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val jobs = verify.batch().v1().jobs().inNamespace("default").list().items
            val created = jobs.firstOrNull { it.metadata?.name?.startsWith("nightly-manual-") == true }
            assertNotNull(created, "expected a Job created by triggerCronJob")

            val labels = created.metadata?.labels ?: emptyMap()
            val annotations = created.metadata?.annotations ?: emptyMap()

            // Labels from jobTemplate.metadata must be propagated (D1 fix).
            assertEquals("backup", labels["app"], "label 'app' from jobTemplate must be copied to Job")
            // Annotations from jobTemplate.metadata must be propagated (D1 fix).
            assertEquals("infra", annotations["team"], "annotation 'team' from jobTemplate must be copied to Job")
            // Our always-present annotation must be set (and must win over any clash).
            assertEquals(
                "manual",
                annotations["cronjob.kubernetes.io/instantiate"],
                "instantiate annotation must be 'manual'",
            )
        } finally {
            verify.close()
        }
    }

    @Test
    fun `triggerCronJob makes the CronJob the controller owner of the Job, without blockOwnerDeletion`() {
        val result = client.actions.triggerCronJob("nightly", "default")
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val cronJobUid = verify.batch().v1().cronjobs().inNamespace("default").withName("nightly").get().metadata.uid
            assertFalse(cronJobUid.isNullOrBlank(), "the seeded CronJob must have a uid")

            val jobs = verify.batch().v1().jobs().inNamespace("default").list().items
            val created = jobs.firstOrNull { it.metadata?.name?.startsWith("nightly-manual-") == true }
            assertNotNull(created, "expected a Job created by triggerCronJob")

            val owners = created.metadata?.ownerReferences.orEmpty()
            assertEquals(1, owners.size, "expected exactly one ownerReference, got: $owners")
            val owner = owners.single()
            assertEquals("batch/v1", owner.apiVersion)
            assertEquals("CronJob", owner.kind)
            assertEquals("nightly", owner.name)
            assertEquals(cronJobUid, owner.uid)
            assertEquals(true, owner.controller)
            assertNull(owner.blockOwnerDeletion, "blockOwnerDeletion needs the cronjobs/finalizers permission")
        } finally {
            verify.close()
        }
    }

    @Test
    fun `triggerCronJob fails before creating a Job when the CronJob has no uid`() {
        server.expect().get().withPath("/apis/batch/v1/namespaces/default/cronjobs/no-uid").andReturn(
            200,
            CronJobBuilder()
                .withNewMetadata().withName("no-uid").withNamespace("default").endMetadata()
                .withNewSpec()
                .withJobTemplate(
                    JobTemplateSpecBuilder()
                        .withNewSpec()
                        .withNewTemplate()
                        .withNewSpec()
                        .addNewContainer()
                        .withName("worker")
                        .withImage("busybox")
                        .endContainer()
                        .withRestartPolicy("Never")
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build(),
                )
                .endSpec()
                .build(),
        ).once()

        val result = client.actions.triggerCronJob("no-uid", "default")
        assertTrue(result.isFailure, "expected failure for a CronJob without a uid")
        assertEquals("CronJob has no uid", result.exceptionOrNull()?.message)

        val verify = server.createClient()
        try {
            val jobs = verify.batch().v1().jobs().inNamespace("default").list().items
            assertTrue(
                jobs.none { it.metadata?.name?.startsWith("no-uid-manual-") == true },
                "no Job may be created for a CronJob without a uid, got: ${jobs.map { it.metadata?.name }}",
            )
        } finally {
            verify.close()
        }
    }

    @Test
    fun `triggerCronJob truncates the Job name so it stays within 63 characters`() {
        val longName = "a".repeat(60)
        val result = client.actions.triggerCronJob(longName, "default")
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val jobs = verify.batch().v1().jobs().inNamespace("default").list().items
            for (job in jobs) {
                val jobName = job.metadata?.name ?: continue
                assertTrue(
                    jobName.length <= 63,
                    "Job name '$jobName' is ${jobName.length} chars — must be <= 63",
                )
            }
        } finally {
            verify.close()
        }
    }

    @Test
    fun `triggerCronJob returns failure when the CronJob does not exist`() {
        val result = client.actions.triggerCronJob("missing", "default")
        assertTrue(result.isFailure, "expected failure for missing CronJob")
    }
}
