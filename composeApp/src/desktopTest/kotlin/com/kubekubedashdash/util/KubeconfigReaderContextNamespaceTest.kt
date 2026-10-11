package com.kubekubedashdash.util

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [KubeconfigReader.contextNamespace]: the `namespace:` a kubeconfig context starts in, which a
 * namespace-limited account falls back to when the cluster refuses the namespace list. A
 * plain YAML read with the reader's merge rules (first file wins); anything that is not a valid
 * namespace name, an unknown context or an unreadable file reads as null and never throws.
 * Temp files only.
 */
class KubeconfigReaderContextNamespaceTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kkdd-kubeconfig-context-namespace").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** One context named [name]; [namespaceLine] is the raw YAML line under `context:`, or null for none. */
    private fun kubeconfig(name: String, namespaceLine: String?): String = buildString {
        appendLine("apiVersion: v1")
        appendLine("kind: Config")
        appendLine("contexts:")
        appendLine("- name: $name")
        appendLine("  context:")
        appendLine("    cluster: cluster-x")
        appendLine("    user: user-x")
        if (namespaceLine != null) appendLine("    $namespaceLine")
        appendLine("users:")
        appendLine("- name: user-x")
        appendLine("  user:")
        appendLine("    token: fake-token-1")
    }

    private fun write(name: String, content: String): File = File(dir, name).also { it.writeText(content) }

    private fun reader(vararg files: File) = KubeconfigReader { files.map { it.path } }

    @Test
    fun `a context with a namespace returns it`() {
        val file = write("a", kubeconfig("example-context", "namespace: team-a"))
        assertEquals("team-a", reader(file).contextNamespace("example-context"))
    }

    @Test
    fun `the namespace is trimmed`() {
        val file = write("a", kubeconfig("example-context", "namespace: \" team-a \""))
        assertEquals("team-a", reader(file).contextNamespace("example-context"))
    }

    @Test
    fun `a context without a namespace returns null`() {
        val file = write("a", kubeconfig("example-context", null))
        assertNull(reader(file).contextNamespace("example-context"))
    }

    @Test
    fun `a blank namespace returns null`() {
        val file = write("a", kubeconfig("example-context", "namespace: \"\""))
        assertNull(reader(file).contextNamespace("example-context"))
    }

    @Test
    fun `an invalid namespace name returns null`() {
        val file = write("a", kubeconfig("example-context", "namespace: Team_A"))
        assertNull(reader(file).contextNamespace("example-context"))
    }

    @Test
    fun `two files, the first file's definition wins`() {
        val first = write("a", kubeconfig("example-context", "namespace: team-a"))
        val second = write("b", kubeconfig("example-context", "namespace: team-b"))
        assertEquals("team-a", reader(first, second).contextNamespace("example-context"))
    }

    @Test
    fun `a first definition without a namespace is not completed by a later file`() {
        val first = write("a", kubeconfig("example-context", null))
        val second = write("b", kubeconfig("example-context", "namespace: team-b"))
        assertNull(reader(first, second).contextNamespace("example-context"))
    }

    @Test
    fun `a context only the second file defines is found there`() {
        val first = write("a", kubeconfig("other-context", "namespace: team-a"))
        val second = write("b", kubeconfig("example-context", "namespace: team-b"))
        assertEquals("team-b", reader(first, second).contextNamespace("example-context"))
    }

    @Test
    fun `an unknown context returns null`() {
        val file = write("a", kubeconfig("example-context", "namespace: team-a"))
        assertNull(reader(file).contextNamespace("no-such-context"))
    }

    @Test
    fun `a malformed file returns null and does not throw`() {
        val malformed = write("malformed", "{{{ this is not a kubeconfig")
        assertNull(reader(malformed).contextNamespace("example-context"))
    }

    @Test
    fun `a missing file returns null and does not throw`() {
        assertNull(KubeconfigReader { listOf(File(dir, "missing").path) }.contextNamespace("example-context"))
    }
}
