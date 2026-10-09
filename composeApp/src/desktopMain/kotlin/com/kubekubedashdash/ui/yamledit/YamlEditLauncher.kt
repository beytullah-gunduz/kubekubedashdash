package com.kubekubedashdash.ui.yamledit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.edit_filled
import com.kubekubedashdash.ui.LocalClusterSession
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.ui.feedback.LocalActionFeedback
import com.kubekubedashdash.ui.screens.DetailAction
import com.kubekubedashdash.util.SecretYamlMasking
import com.kubekubedashdash.yamledit.EditableKinds
import com.kubekubedashdash.yamledit.YamlWriter
import com.kubekubedashdash.yamledit.session.YamlEditRegistry
import com.kubekubedashdash.yamledit.session.YamlEditSession
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What an Edit button needs for one object: whether it can be clicked ([enabled]: connected, inside
 * a cluster tab), whether an editor is already open for it ([editing], state-backed), what a click
 * does ([open], which brings an open editor forward instead of opening a second) and the way to
 * that editor ([focusExisting]).
 *
 * Editing a core Secret with masking on first asks for consent; the question is [Dialog], which
 * the screen that shows the Edit button renders wherever it likes (a member composable can).
 */
class YamlEditEntry(
    val enabled: Boolean,
    val editing: Boolean,
    val open: () -> Unit,
    val focusExisting: () -> Unit,
    private val consentVisible: MutableState<Boolean>,
    private val onConsent: () -> Unit,
) {
    /** The Secret consent dialog (D6); renders nothing unless [open] asked for it. */
    @Composable
    fun Dialog() {
        if (consentVisible.value) {
            ConfirmActionDialog(
                title = "Edit Secret?",
                body = "The editor window shows this Secret's values unmasked (base64-encoded). Masking stays on everywhere else.",
                confirmLabel = "Show values and edit",
                destructive = false,
                onConfirm = {
                    consentVisible.value = false
                    onConsent()
                },
                onDismiss = { consentVisible.value = false },
            )
        }
    }
}

/**
 * The Edit entry for a YAML tab or a detail header, or null when the object can't be edited
 * ([EditableKinds.targetFor]: an Event, a kind the editor doesn't know). Outside a cluster tab (no
 * [LocalClusterSession]) the entry exists but is disabled.
 *
 * A click opens the editor on the EDT: the registry makes the Swing buffer there and the session
 * loads on its own scope, so nothing blocks. The editor's toasts go to the window this was composed
 * in ([LocalActionFeedback]).
 */
@Composable
fun rememberYamlEditEntry(
    kind: String,
    name: String,
    namespace: String?,
    group: String?,
    version: String?,
    plural: String?,
    registry: YamlEditRegistry = YamlEditRegistry.Default,
): YamlEditEntry? {
    val target = remember(kind, name, namespace, group, version, plural) {
        EditableKinds.targetFor(kind, name, namespace, group, version, plural)
    }
    val session = LocalClusterSession.current
    val feedback = LocalActionFeedback.current
    val connected = LocalIsConnected.current
    val windows by registry.windows.collectAsState()
    val consentVisible = remember(target?.key) { mutableStateOf(false) }
    if (target == null) return null
    if (session == null) return YamlEditEntry(false, false, {}, {}, consentVisible, {})

    val editing = windows.any { it is YamlEditSession && it.clusterSessionId == session.id && it.target.key == target.key }
    val focusExisting: () -> Unit = { registry.editFor(session.id, target.key)?.requestFocus() }
    val openNow: () -> Unit = {
        // Lock-free, so safe on the EDT; null while the tab is not connected.
        session.connectionManager.connectedContextOrNull()?.let { context ->
            registry.openEdit(
                clusterSessionId = session.id,
                context = context,
                target = target,
                writer = YamlWriter(session.connectionManager),
                feedback = feedback,
                masking = { PreferenceRepository.maskSecretValues.value },
                bufferFactory = { RstaBuffer() },
            )
        }
    }
    val isCoreSecret = target.group.isEmpty() && SecretYamlMasking.isSecretKind(target.kind)
    val open: () -> Unit = {
        if (registry.editFor(session.id, target.key) != null) {
            // One editor per object: an open one comes forward, with no second consent for a Secret.
            focusExisting()
        } else if (isCoreSecret && PreferenceRepository.maskSecretValues.value) {
            consentVisible.value = true
        } else {
            openNow()
        }
    }
    return YamlEditEntry(
        enabled = connected,
        editing = editing,
        open = open,
        focusExisting = focusExisting,
        consentVisible = consentVisible,
        onConsent = openNow,
    )
}

/** The detail header's "Edit YAML" verb for [entry]. */
fun yamlEditDetailAction(entry: YamlEditEntry): DetailAction = DetailAction(
    label = "Edit YAML",
    icon = Res.drawable.edit_filled,
    description = "Edit this object's YAML in a separate window, review the diff and a server dry run, then apply.",
    enabled = entry.enabled,
    onClick = entry.open,
)

/** Stands for a missing session's connection flag: never connected. */
private val NeverConnected = MutableStateFlow(false)

/**
 * What the "Apply YAML" button and palette entry run for [session], or null while there is no
 * session or it is not connected (the control then is not offered). Running it opens the Apply YAML
 * window of the session's tab and cluster, or brings the open one forward (one per tab and cluster).
 *
 * The window's default namespace, given to a document that names none, is the tab's one selected
 * namespace (D12), else `default`; it is read when the window opens. Its CRD list is the tab's
 * informer as it stands when a review starts. Both ask the session's reactive client, so the
 * button works from any screen of the tab. The window's toasts go to the window this was composed in.
 */
@Composable
fun rememberApplyYamlOpener(session: ClusterSession?, registry: YamlEditRegistry = YamlEditRegistry.Default): (() -> Unit)? {
    val feedback = LocalActionFeedback.current
    val connected by (session?.viewModel?.isConnected ?: NeverConnected).collectAsState()
    return remember(session, feedback, registry, connected) {
        if (session == null || !connected) {
            null
        } else {
            val open: () -> Unit = {
                // Lock-free, so safe on the EDT; null when the connection dropped since the button was drawn.
                session.connectionManager.connectedContextOrNull()?.let { context ->
                    registry.openApply(
                        clusterSessionId = session.id,
                        context = context,
                        defaultNamespace = session.reactiveClient.namespaceScope.value.serverNamespace ?: "default",
                        writer = YamlWriter(session.connectionManager),
                        crds = { (session.reactiveClient.crds.value as? ResourceState.Success)?.data.orEmpty() },
                        feedback = feedback,
                        masking = { PreferenceRepository.maskSecretValues.value },
                        bufferFactory = { RstaBuffer() },
                    )
                }
            }
            open
        }
    }
}
