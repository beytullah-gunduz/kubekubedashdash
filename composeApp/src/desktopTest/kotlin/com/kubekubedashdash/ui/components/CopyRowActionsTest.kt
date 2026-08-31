package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun recorder(): Pair<CopyToClipboard, MutableList<Pair<String, String>>> {
    val log = mutableListOf<Pair<String, String>>()
    return CopyToClipboard { text, label -> log += text to label } to log
}

class CopyRowActionsTest {
    @Test
    fun `no targets returns no actions`() {
        val (copy, _) = recorder()
        assertEquals(emptyList(), copyRowActions(emptyList(), copy))
    }

    @Test
    fun `single target with no kind and no namespace offers only copy name`() {
        val (copy, _) = recorder()
        val actions = copyRowActions(listOf(RowIdentity(kind = null, name = "demo", namespace = null)), copy)
        assertEquals(listOf("Copy name"), actions.map { it.label })
    }

    @Test
    fun `single target with no kind but a namespace offers copy name and copy namespace`() {
        val (copy, _) = recorder()
        val actions = copyRowActions(listOf(RowIdentity(kind = null, name = "demo", namespace = "team-a")), copy)
        assertEquals(listOf("Copy name", "Copy namespace"), actions.map { it.label })
    }

    @Test
    fun `single namespaced target offers kubectl items with the -n fragment`() {
        val (copy, log) = recorder()
        val actions = copyRowActions(listOf(RowIdentity("Pod", "api-0", "team-a")), copy)
        assertEquals(
            listOf("Copy name", "Copy namespace", "Copy kubectl get", "Copy kubectl describe"),
            actions.map { it.label },
        )
        actions.first { it.label == "Copy kubectl get" }.onSelect()
        assertEquals("kubectl get pod api-0 -n team-a" to "Copied command", log.last())

        // Pin the payloads too, not just the labels: asserting labels alone would
        // let Copy name and Copy namespace swap their arguments undetected.
        actions.first { it.label == "Copy name" }.onSelect()
        assertEquals("api-0" to "Copied", log.last())
        actions.first { it.label == "Copy namespace" }.onSelect()
        assertEquals("team-a" to "Copied", log.last())
        actions.first { it.label == "Copy kubectl describe" }.onSelect()
        assertEquals("kubectl describe pod api-0 -n team-a" to "Copied command", log.last())
    }

    @Test
    fun `single cluster-scoped target has no namespace item and no -n fragment`() {
        val (copy, log) = recorder()
        val actions = copyRowActions(listOf(RowIdentity("Node", "node-1", null)), copy)
        assertEquals(listOf("Copy name", "Copy kubectl get", "Copy kubectl describe"), actions.map { it.label })
        actions.first { it.label == "Copy kubectl get" }.onSelect()
        assertEquals("kubectl get node node-1" to "Copied command", log.last())
    }

    @Test
    fun `kind is lowercased in the kubectl payload`() {
        val (copy, log) = recorder()
        val actions = copyRowActions(listOf(RowIdentity("Pod", "api-0", "team-a")), copy)
        actions.first { it.label == "Copy kubectl get" }.onSelect()
        assertTrue(log.last().first.contains("pod"))
    }

    @Test
    fun `multiple targets with same kind and namespace offer the multi-name kubectl items`() {
        val (copy, log) = recorder()
        val targets = listOf(
            RowIdentity("Pod", "a", "team-a"),
            RowIdentity("Pod", "b", "team-a"),
            RowIdentity("Pod", "c", "team-a"),
        )
        val actions = copyRowActions(targets, copy)
        assertEquals(
            listOf("Copy 3 names", "Copy kubectl get", "Copy kubectl describe"),
            actions.map { it.label },
        )

        actions.first { it.label == "Copy 3 names" }.onSelect()
        assertEquals("a\nb\nc" to "Copied 3 names", log.last())

        actions.first { it.label == "Copy kubectl get" }.onSelect()
        assertEquals("kubectl get pod a b c -n team-a" to "Copied command", log.last())
    }

    @Test
    fun `multiple targets with mixed kinds only offer copy names`() {
        val (copy, _) = recorder()
        val targets = listOf(
            RowIdentity("Pod", "a", "team-a"),
            RowIdentity("Node", "b", "team-a"),
            RowIdentity("Pod", "c", "team-a"),
        )
        assertEquals(listOf("Copy 3 names"), copyRowActions(targets, copy).map { it.label })
    }

    @Test
    fun `multiple targets with mixed namespaces only offer copy names`() {
        val (copy, _) = recorder()
        val targets = listOf(
            RowIdentity("Pod", "a", "team-a"),
            RowIdentity("Pod", "b", "team-b"),
            RowIdentity("Pod", "c", "team-a"),
        )
        assertEquals(listOf("Copy 3 names"), copyRowActions(targets, copy).map { it.label })
    }

    @Test
    fun `multiple targets with one null kind only offer copy names`() {
        val (copy, _) = recorder()
        val targets = listOf(
            RowIdentity("Pod", "a", "team-a"),
            RowIdentity(null, "b", "team-a"),
            RowIdentity("Pod", "c", "team-a"),
        )
        assertEquals(listOf("Copy 3 names"), copyRowActions(targets, copy).map { it.label })
    }

    @Test
    fun `blank name is treated as absent`() {
        val (copy, _) = recorder()
        assertEquals(emptyList(), copyRowActions(listOf(RowIdentity("Pod", "", "team-a")), copy))
    }

    @Test
    fun `blank kind is treated as null and suppresses kubectl items`() {
        val (copy, _) = recorder()
        val actions = copyRowActions(listOf(RowIdentity("", "demo", "team-a")), copy)
        assertEquals(listOf("Copy name", "Copy namespace"), actions.map { it.label })
    }

    @Test
    fun `blank namespace is treated as null and suppresses namespace and -n`() {
        val (copy, log) = recorder()
        val actions = copyRowActions(listOf(RowIdentity("Pod", "demo", "")), copy)
        assertEquals(listOf("Copy name", "Copy kubectl get", "Copy kubectl describe"), actions.map { it.label })
        actions.first { it.label == "Copy kubectl get" }.onSelect()
        assertTrue(!log.last().first.contains("-n"))
    }
}
