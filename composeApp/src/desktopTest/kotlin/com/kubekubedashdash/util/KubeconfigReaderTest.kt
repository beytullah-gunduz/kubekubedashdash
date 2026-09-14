package com.kubekubedashdash.util

import com.kubekubedashdash.ui.modals.viewmodel.DefaultEksDiscoveryGateway
import com.kubekubedashdash.ui.modals.viewmodel.DefaultGkeDiscoveryGateway
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [KubeconfigReader] semantics: every `$KUBECONFIG` entry in order, names
 * deduplicated with the FIRST file's definition winning (fabric8's merge rule
 * and kubectl's, so the binding shown is the one a connect will use),
 * current-context from the FIRST file that sets one (kubectl's rule and
 * fabric8's context preference), nameless or bodiless entries and a dangling
 * current-context dropped as fabric8 drops them, unreadable or malformed
 * files skipped, and no exec plugin ever run. Also the locator's `$KUBECONFIG` splitting and the callers that now
 * read through the reader without a session. Temp files only; the
 * `kubeconfig` system property is restored after each test.
 */
class KubeconfigReaderTest {

    private data class Ctx(val name: String, val awsProfile: String? = null)

    private lateinit var dir: File
    private lateinit var marker: File
    private var previousProperty: String? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kkdd-kubeconfig-reader").toFile()
        marker = File(dir, "plugin-ran")
        previousProperty = System.getProperty("kubeconfig")
    }

    @AfterTest
    fun tearDown() {
        val previous = previousProperty
        if (previous == null) System.clearProperty("kubeconfig") else System.setProperty("kubeconfig", previous)
        dir.deleteRecursively()
    }

    @Test
    fun `context names come from every file in order and are deduplicated`() {
        val a = write("a", kubeconfig("ctx-1", Ctx("ctx-1"), Ctx("ctx-2")))
        val b = write("b", kubeconfig(null, Ctx("ctx-2"), Ctx("ctx-3")))
        val reader = KubeconfigReader { listOf(a.path, b.path) }
        assertEquals(listOf("ctx-1", "ctx-2", "ctx-3"), reader.contextNames())
    }

    @Test
    fun `a context defined in two files takes the first file's binding`() {
        val a = write("a", kubeconfig("ctx-shared", Ctx("ctx-shared", awsProfile = "profile-a")))
        val b = write("b", kubeconfig(null, Ctx("ctx-shared", awsProfile = "profile-b"), Ctx("ctx-other")))
        val bindings = KubeconfigReader { listOf(a.path, b.path) }.contextBindings()
        assertEquals(
            listOf(ContextBinding("ctx-shared", "profile-a"), ContextBinding("ctx-other", null)),
            bindings,
        )
    }

    @Test
    fun `the current context is the first file's that sets one`() {
        val a = write("a", kubeconfig(null, Ctx("ctx-1")))
        val b = write("b", kubeconfig("ctx-3", Ctx("ctx-3")))
        val c = write("c", kubeconfig("ctx-1", Ctx("ctx-1")))
        assertEquals("ctx-3", KubeconfigReader { listOf(a.path, b.path, c.path) }.currentContext())
        assertEquals("", KubeconfigReader { listOf(a.path) }.currentContext())
    }

    @Test
    fun `missing and malformed files are skipped`() {
        val missing = File(dir, "missing").path
        val malformed = write("malformed", "{{{ this is not a kubeconfig")
        val good = write("good", kubeconfig("ctx-1", Ctx("ctx-1")))
        val reader = KubeconfigReader { listOf(missing, malformed.path, good.path) }
        assertEquals(listOf("ctx-1"), reader.contextNames())
        assertEquals("ctx-1", reader.currentContext())
        assertEquals(emptyList(), KubeconfigReader { listOf(missing) }.contextNames())
    }

    @Test
    fun `entries without a name or a body are dropped and a dangling current-context reads as empty`() {
        val raw = write(
            "raw",
            """
            apiVersion: v1
            kind: Config
            current-context: ghost
            contexts:
            - context:
                cluster: cluster-x
                user: user-x
            - name: headless
            - name: ctx-ok
              context:
                cluster: cluster-x
                user: user-x
            users:
            - user:
                token: placeholder
            - name: user-x
            """.trimIndent(),
        )
        val reader = KubeconfigReader { listOf(raw.path) }
        assertEquals(listOf("ctx-ok"), reader.contextNames())
        assertEquals("", reader.currentContext(), "a current-context naming no known context must read as empty")
        assertEquals(listOf(ContextBinding("ctx-ok", null)), reader.contextBindings())
    }

    @Test
    fun `a dangling current-context in the first file yields to the next file's`() {
        val a = write("a", kubeconfig("ghost", Ctx("ctx-a")))
        val b = write("b", kubeconfig("ctx-b", Ctx("ctx-b")))
        assertEquals("ctx-b", KubeconfigReader { listOf(a.path, b.path) }.currentContext())
    }

    @Test
    fun `a stray exec env entry costs nothing but its own profile`() {
        val raw = write(
            "raw",
            """
            apiVersion: v1
            kind: Config
            current-context: ctx-a
            contexts:
            - name: ctx-a
              context:
                cluster: cluster-x
                user: user-a
            - name: ctx-b
              context:
                cluster: cluster-x
                user: user-b
            users:
            - name: user-a
              user:
                exec:
                  apiVersion: client.authentication.k8s.io/v1beta1
                  command: /bin/sh
                  env:
                  -
                  - name: AWS_PROFILE
                    value: example-profile
            - name: user-b
              user:
                token: placeholder
            """.trimIndent(),
        )
        val reader = KubeconfigReader { listOf(raw.path) }
        assertEquals(listOf(ContextBinding("ctx-a", "example-profile"), ContextBinding("ctx-b", null)), reader.contextBindings())
        assertEquals("ctx-a", reader.currentContext())
    }

    @Test
    fun `a blank kubeconfig property counts as unset`() {
        System.setProperty("kubeconfig", "  ")
        assertEquals(
            KubeconfigLocator.splitSpec(System.getenv("KUBECONFIG"), System.getProperty("user.home")),
            KubeconfigLocator.allPaths(),
        )
    }

    @Test
    fun `no listing runs an exec credential plugin`() {
        val a = write("a", kubeconfig("ctx-exec", Ctx("ctx-exec", awsProfile = "example-profile")))
        val reader = KubeconfigReader { listOf(a.path) }
        reader.contextNames()
        reader.currentContext()
        reader.contextBindings()
        assertFalse(marker.exists(), "the reader must never execute an exec credential plugin")
    }

    @Test
    fun `the locator splits the KUBECONFIG spec and drops blank entries`() {
        val sep = File.pathSeparator
        assertEquals(listOf("/a/config", "/b/config"), KubeconfigLocator.splitSpec("/a/config$sep$sep/b/config$sep ", "/home/example"))
        assertEquals(listOf("/home/example/.kube/config"), KubeconfigLocator.splitSpec(null, "/home/example"))
        assertEquals(listOf("/home/example/.kube/config"), KubeconfigLocator.splitSpec("  ", "/home/example"))
    }

    @Test
    fun `the active path is the first entry even when it does not exist yet`() {
        val missing = File(dir, "missing").path
        val good = write("good", kubeconfig("ctx-1", Ctx("ctx-1")))
        System.setProperty("kubeconfig", "$missing${File.pathSeparator}${good.path}")
        assertEquals(listOf(missing, good.path), KubeconfigLocator.allPaths())
        assertEquals(missing, KubeconfigLocator.activePath())
    }

    @Test
    fun `the discovery gateways list contexts without a session or an exec plugin`() {
        val a = write("a", kubeconfig("ctx-exec", Ctx("ctx-exec", awsProfile = "example-profile"), Ctx("ctx-plain")))
        System.setProperty("kubeconfig", a.path)
        assertEquals(listOf("ctx-exec", "ctx-plain"), DefaultEksDiscoveryGateway().existingContexts())
        assertEquals(listOf("ctx-exec", "ctx-plain"), DefaultGkeDiscoveryGateway().existingContexts())
        assertFalse(marker.exists(), "listing existing contexts must not execute the exec credential plugin")
    }

    @Test
    fun `the prerequisite kubeconfig check passes when any KUBECONFIG entry is readable`() {
        val missing = File(dir, "missing").path
        val good = write("good", kubeconfig("ctx-1", Ctx("ctx-1"), Ctx("ctx-2")))
        System.setProperty("kubeconfig", "$missing${File.pathSeparator}${good.path}")
        val result = PrerequisiteChecker.runAll()
        val kubeconfigCheck = result.checks.first { it.name == PrerequisiteChecker.NAME_KUBECONFIG }
        assertEquals(CheckStatus.PASSED, kubeconfigCheck.status, "detail: ${kubeconfigCheck.detail}")
        // The raw path: this fixture lives in the system temp directory, outside user.home,
        // so the home-relative rendering (F8, displayPath) leaves it unchanged.
        assertEquals(good.path, kubeconfigCheck.detail)
        val contexts = result.checks.first { it.name == PrerequisiteChecker.NAME_CONTEXTS }
        assertEquals(CheckStatus.PASSED, contexts.status, "detail: ${contexts.detail}")
        assertEquals("2 contexts found", contexts.detail)
    }

    private fun write(name: String, body: String): File = File(dir, name).apply { writeText(body) }

    /** A kubeconfig whose exec users' plugin only touches [marker]; a user without a profile carries a placeholder token. */
    private fun kubeconfig(currentContext: String?, vararg contexts: Ctx): String = buildString {
        // Single-quoted YAML for the plugin args: a double-quoted scalar reads the
        // backslashes of a Windows temp path as escapes and the whole file fails to parse.
        val markerPath = marker.absolutePath.replace("'", "''")
        appendLine("apiVersion: v1")
        appendLine("kind: Config")
        if (currentContext != null) appendLine("current-context: $currentContext")
        appendLine("clusters:")
        contexts.forEach {
            appendLine("- name: cluster-${it.name}")
            appendLine("  cluster:")
            appendLine("    server: https://127.0.0.1:1")
        }
        appendLine("contexts:")
        contexts.forEach {
            appendLine("- name: ${it.name}")
            appendLine("  context:")
            appendLine("    cluster: cluster-${it.name}")
            appendLine("    user: user-${it.name}")
        }
        appendLine("users:")
        contexts.forEach {
            appendLine("- name: user-${it.name}")
            appendLine("  user:")
            if (it.awsProfile != null) {
                appendLine("    exec:")
                appendLine("      apiVersion: client.authentication.k8s.io/v1beta1")
                appendLine("      command: /bin/sh")
                appendLine("      args: ['-c', 'touch \"$markerPath\"']")
                appendLine("      env:")
                appendLine("      - name: AWS_PROFILE")
                appendLine("        value: ${it.awsProfile}")
            } else {
                appendLine("    token: placeholder")
            }
        }
    }
}
