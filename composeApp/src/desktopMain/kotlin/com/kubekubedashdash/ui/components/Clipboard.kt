package com.kubekubedashdash.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/**
 * Identity of the Kubernetes object a row represents. When a [TableRow] carries
 * one, `ResourceTable` synthesizes the copy items at the top of that row's menu.
 *
 * A null [kind] means the object cannot be addressed by kubectl from this view
 * (unknown kind, or a row aggregated from another cluster) and suppresses the
 * kubectl items. A null [namespace] means cluster-scoped — no `-n` fragment.
 */
data class RowIdentity(
    val kind: String?,
    val name: String,
    val namespace: String? = null,
)

/**
 * Copies to the system clipboard and raises a transient confirmation pill.
 *
 * A class rather than a function type so [invoke] can default its label:
 * Kotlin function types cannot carry default parameter values, and every
 * existing `copyToClipboard(text)` call site must keep compiling unchanged.
 */
class CopyToClipboard internal constructor(
    private val onCopy: (String, String) -> Unit,
) {
    operator fun invoke(text: String, label: String = "Copied") = onCopy(text, label)
}

/**
 * Returns a fire-and-forget callback that copies a string to the system
 * clipboard and shows the "Copied" pill. Wraps [LocalClipboard]'s suspend API
 * so onClick handlers can call it synchronously without each call site
 * spelling out the coroutine plumbing.
 */
@Composable
fun rememberCopyToClipboard(): CopyToClipboard {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val feedback = LocalCopyFeedback.current
    return remember(clipboard, scope, feedback) {
        CopyToClipboard { text, label ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(StringSelection(text)))
            }
            feedback(label)
        }
    }
}

/**
 * Builds the copy items for a row menu. Pure — all clipboard side effects go
 * through [copy], so this is unit-testable with a recording double.
 *
 * A blank name, blank kind or blank namespace is treated exactly as absent.
 */
internal fun copyRowActions(
    targets: List<RowIdentity>,
    copy: CopyToClipboard,
): List<RowAction> {
    val clean = targets
        .filter { it.name.isNotBlank() }
        .map {
            RowIdentity(
                kind = it.kind?.takeIf { k -> k.isNotBlank() },
                name = it.name,
                namespace = it.namespace?.takeIf { ns -> ns.isNotBlank() },
            )
        }
    if (clean.isEmpty()) return emptyList()

    val names = clean.joinToString("\n") { it.name }
    val kind = clean.first().kind
    val namespace = clean.first().namespace
    val uniform = clean.all { it.kind == kind && it.namespace == namespace } && kind != null

    return buildList {
        if (clean.size == 1) {
            add(RowAction("Copy name") { copy(clean.first().name, "Copied") })
            namespace?.let { ns -> add(RowAction("Copy namespace") { copy(ns, "Copied") }) }
        } else {
            add(RowAction("Copy ${clean.size} names") { copy(names, "Copied ${clean.size} names") })
        }
        if (uniform) {
            val args = clean.joinToString(" ") { it.name }
            val suffix = namespace?.let { " -n $it" }.orEmpty()
            val target = "${kind.lowercase()} $args$suffix"
            add(RowAction("Copy kubectl get") { copy("kubectl get $target", "Copied command") })
            add(RowAction("Copy kubectl describe") { copy("kubectl describe $target", "Copied command") })
        }
    }
}
