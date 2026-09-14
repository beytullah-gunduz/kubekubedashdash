package com.kubekubedashdash.util

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Listing kube contexts must not execute anybody's exec credential plugin.
 *
 * fabric8's `Config.autoConfigure` merges the kubeconfig and, for the selected
 * context's user, runs its `exec` block synchronously (`KubeConfigUtils.merge`
 * → `mergeKubeConfigExecCredential` → `ProcessBuilder.start`). Every caller
 * that only wanted context NAMES — the prerequisite check, the cluster picker,
 * the discovery wizards, the session's current-context fallback — therefore
 * spawned `aws` / `gcloud` / `kubelogin` for the kubeconfig's current-context.
 *
 * The kubeconfig under test names the `kubeconfig` system property, which
 * fabric8 (and, after the fix, the app's own locator) consults before
 * `$KUBECONFIG`; the property is restored afterwards. Its current context's
 * user has an exec plugin that only touches a marker file: the marker's
 * absence after a listing is the proof. Everything lives in a temp directory —
 * no real kubeconfig, no real user state.
 */
class KubeconfigDiscoveryNoExecTest {

    private lateinit var dir: File
    private lateinit var marker: File
    private lateinit var kubeconfig: File
    private var previousProperty: String? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kkdd-kubeconfig-noexec").toFile()
        marker = File(dir, "plugin-ran")
        kubeconfig = File(dir, "config").apply { writeText(kubeconfigWithExecPlugin(marker)) }
        previousProperty = System.getProperty("kubeconfig")
        System.setProperty("kubeconfig", kubeconfig.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        val previous = previousProperty
        if (previous == null) System.clearProperty("kubeconfig") else System.setProperty("kubeconfig", previous)
        dir.deleteRecursively()
    }

    @Test
    fun `the current-context fallback reads the kubeconfig without running its exec plugin`() {
        val manager = KubeConnectionManager()
        try {
            assertEquals("ctx-a", manager.getCurrentContext())
            assertFalse(marker.exists(), "reading the current context must not execute the exec credential plugin")
        } finally {
            manager.close()
        }
    }

    @Test
    fun `the prerequisite check counts contexts without running the exec plugin`() {
        val result = PrerequisiteChecker.runAll()
        val contexts = result.checks.first { it.name == PrerequisiteChecker.NAME_CONTEXTS }
        assertEquals(CheckStatus.PASSED, contexts.status, "detail: ${contexts.detail}")
        assertEquals("2 contexts found", contexts.detail)
        assertFalse(marker.exists(), "counting contexts must not execute the exec credential plugin")
    }

    private fun kubeconfigWithExecPlugin(marker: File): String {
        // Single-quoted YAML for the plugin args: a double-quoted scalar reads the
        // backslashes of a Windows temp path as escapes and the whole file fails to parse.
        val markerPath = marker.absolutePath.replace("'", "''")
        return """
        apiVersion: v1
        kind: Config
        current-context: ctx-a
        clusters:
        - name: cluster-a
          cluster:
            server: https://127.0.0.1:1
        - name: cluster-b
          cluster:
            server: https://127.0.0.1:2
        contexts:
        - name: ctx-a
          context:
            cluster: cluster-a
            user: user-a
        - name: ctx-b
          context:
            cluster: cluster-b
            user: user-b
        users:
        - name: user-a
          user:
            exec:
              apiVersion: client.authentication.k8s.io/v1beta1
              command: /bin/sh
              args: ['-c', 'touch "$markerPath"']
              env:
              - name: AWS_PROFILE
                value: example-profile
        - name: user-b
          user:
            token: placeholder
        """.trimIndent()
    }
}
