package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobTemplateSpecBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 */
class ReactiveKubeClientTriggerCronJobTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            HashMap(),
            KubernetesCrudDispatcher(),
            false,
        )
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
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
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
