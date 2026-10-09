package com.kubekubedashdash.services

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.services.session.SessionPersistence
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.yamledit.YamlWriter
import com.kubekubedashdash.yamledit.session.ApplyYamlSession
import com.kubekubedashdash.yamledit.session.DirtyEdit
import com.kubekubedashdash.yamledit.session.DiscardPrompt
import com.kubekubedashdash.yamledit.session.GuardVerb
import com.kubekubedashdash.yamledit.session.QuitGuard
import com.kubekubedashdash.yamledit.session.RecordingFeedback
import com.kubekubedashdash.yamledit.session.StringEditorBuffer
import com.kubekubedashdash.yamledit.session.YamlEditRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Closing a tab or a window, and Cmd+Q, against the open YAML editors, through the real
 * [WorkspaceManager] and the process-wide [YamlEditRegistry.Default] (the guard logic itself is
 * covered in `yamledit.session`; this pins the wiring: which sessions each verb guards, where the
 * prompt appears and that the answers do what they say).
 *
 * No real user state is touched: session persistence is switched off before the manager's first use
 * (as the screenshot driver does), the Gradle test task points the data directory at a build
 * directory, nothing connects to a cluster, and every window and editor a test opens is closed again.
 */
class CloseTabPromptWiringTest {

    private val dirtyText = "kind: ConfigMap\n"
    private val workspaces = mutableListOf<Workspace>()

    @BeforeTest
    fun setUp() {
        SessionPersistence.disable()
        QuitGuard.resetForTest()
        YamlEditRegistry.Default.closeAll()
    }

    @AfterTest
    fun tearDown() {
        QuitGuard.resetForTest()
        YamlEditRegistry.Default.closeAll()
        workspaces.forEach { WorkspaceManager.closeWorkspace(it.id) }
    }

    /** A window with [tabs] cluster tabs, registered with the manager; the last one is active. */
    private fun window(tabs: Int): Pair<Workspace, List<ClusterSession>> {
        val workspace = Workspace()
        val sessions = List(tabs) { ClusterSession() }
        sessions.forEach { workspace.addSession(it, makeActive = true) }
        WorkspaceManager.addWorkspace(workspace)
        workspaces += workspace
        return workspace to sessions
    }

    private fun key(session: ClusterSession) = WorkspaceTab.Cluster(session).key

    private fun tabKeys(workspace: Workspace) = workspace.tabs.value.map { it.key }

    /** An Apply YAML editor on [session]'s tab holding [text]: dirty unless blank. No cluster is needed. */
    private fun openEditor(session: ClusterSession, text: String = dirtyText, context: String = "cluster-a"): ApplyYamlSession = YamlEditRegistry.Default.openApply(
        clusterSessionId = session.id,
        context = context,
        defaultNamespace = "default",
        writer = YamlWriter(KubeConnectionManager()),
        crds = { emptyList() },
        feedback = RecordingFeedback(),
        masking = { false },
        bufferFactory = { StringEditorBuffer(text) },
        scopeFactory = { CoroutineScope(SupervisorJob()) },
    )

    private fun openEditors() = YamlEditRegistry.Default.windows.value

    @Test
    fun `closing a tab with nothing unsaved closes it and its editors without asking`() {
        val (workspace, sessions) = window(tabs = 2)
        val (first, second) = sessions
        openEditor(first, text = "")
        val other = openEditor(second, text = dirtyText)

        WorkspaceManager.requestCloseTab(workspace, key(first))

        assertNull(workspace.discardPrompt.value)
        assertEquals(listOf(key(second)), tabKeys(workspace))
        assertEquals(listOf(other.id), openEditors().map { it.id }, "only the closed tab's editor went; the other tab's editor is untouched")
    }

    @Test
    fun `closing a tab with an unsaved editor asks first and keeping the edits changes nothing`() {
        val (workspace, sessions) = window(tabs = 2)
        val (first, second) = sessions
        val editor = openEditor(first)

        WorkspaceManager.requestCloseTab(workspace, key(first))

        val prompt = assertNotNull(workspace.discardPrompt.value)
        assertEquals(GuardVerb.CloseTab, prompt.verb)
        assertEquals(listOf(DirtyEdit(editor.id, first.id, "Apply YAML (cluster-a)", "cluster-a")), prompt.edits)
        assertEquals(listOf(key(first), key(second)), tabKeys(workspace), "the tab waits for the answer")
        assertEquals(listOf(editor.id), openEditors().map { it.id })

        prompt.onKeepEditing()

        assertNull(workspace.discardPrompt.value)
        assertEquals(listOf(key(first), key(second)), tabKeys(workspace))
        assertEquals(listOf(editor.id), openEditors().map { it.id }, "the editor is still there")
    }

    @Test
    fun `discarding closes the tab and its editors`() {
        val (workspace, sessions) = window(tabs = 2)
        val (first, second) = sessions
        openEditor(first)
        openEditor(first, context = "cluster-b", text = "")
        val other = openEditor(second)

        WorkspaceManager.requestCloseTab(workspace, key(first))
        assertNotNull(workspace.discardPrompt.value).onDiscard()

        assertNull(workspace.discardPrompt.value)
        assertEquals(listOf(key(second)), tabKeys(workspace))
        assertEquals(listOf(other.id), openEditors().map { it.id }, "both editors of the closed tab went, the other tab's stayed")
    }

    @Test
    fun `an unsaved editor on another tab does not stop a tab from closing`() {
        val (workspace, sessions) = window(tabs = 2)
        val (first, second) = sessions
        val editor = openEditor(second)

        WorkspaceManager.requestCloseTab(workspace, key(first))

        assertNull(workspace.discardPrompt.value)
        assertEquals(listOf(key(second)), tabKeys(workspace))
        assertEquals(listOf(editor.id), openEditors().map { it.id })
    }

    @Test
    fun `closing a window asks about the editors of any of its tabs`() {
        val (workspace, sessions) = window(tabs = 2)
        val editor = openEditor(sessions[1])

        WorkspaceManager.requestCloseWorkspace(workspace.id)

        val prompt = assertNotNull(workspace.discardPrompt.value)
        assertEquals(GuardVerb.CloseWindow, prompt.verb)
        assertEquals(listOf(editor.id), prompt.edits.map { it.windowId })
        assertTrue(WorkspaceManager.workspaces.value.any { it.id == workspace.id }, "the window stays open until the answer")

        prompt.onKeepEditing()
        assertTrue(WorkspaceManager.workspaces.value.any { it.id == workspace.id })
        assertEquals(listOf(editor.id), openEditors().map { it.id })

        WorkspaceManager.requestCloseWorkspace(workspace.id)
        assertNotNull(workspace.discardPrompt.value).onDiscard()

        assertFalse(WorkspaceManager.workspaces.value.any { it.id == workspace.id })
        assertEquals(emptyList(), openEditors())
    }

    @Test
    fun `closing a window with nothing unsaved closes it and its editors`() {
        val (workspace, sessions) = window(tabs = 1)
        openEditor(sessions[0], text = "")

        WorkspaceManager.requestCloseWorkspace(workspace.id)

        assertFalse(WorkspaceManager.workspaces.value.any { it.id == workspace.id })
        assertEquals(emptyList(), openEditors())
    }

    @Test
    fun `closing a window whose editors are all in another window does not ask`() {
        val (workspace, _) = window(tabs = 1)
        val (_, otherSessions) = window(tabs = 1)
        val editor = openEditor(otherSessions[0])

        WorkspaceManager.requestCloseWorkspace(workspace.id)

        assertFalse(WorkspaceManager.workspaces.value.any { it.id == workspace.id })
        assertEquals(listOf(editor.id), openEditors().map { it.id })
    }

    @Test
    fun `a tab closed by any route takes its editors with it`() {
        val (workspace, sessions) = window(tabs = 2)
        val (first, second) = sessions
        openEditor(first)
        val other = openEditor(second)

        WorkspaceManager.closeTab(workspace, key(first))

        assertEquals(listOf(other.id), openEditors().map { it.id }, "the backstop in closeTab, for routes that did not ask")
    }

    @Test
    fun `a window closed by any route takes the editors of all its tabs with it`() {
        val (workspace, sessions) = window(tabs = 2)
        val (_, otherSessions) = window(tabs = 1)
        sessions.forEach { openEditor(it) }
        val other = openEditor(otherSessions[0])

        WorkspaceManager.closeWorkspace(workspace.id)

        assertEquals(listOf(other.id), openEditors().map { it.id }, "the backstop in closeWorkspace")
    }

    @Test
    fun `the window that asks is the one holding the first unsaved editor, else the first window`() {
        val (_, sessionsA) = window(tabs = 1)
        val (workspaceB, sessionsB) = window(tabs = 1)
        assertSame(WorkspaceManager.workspaces.value.first(), WorkspaceManager.workspaceForPrompt(), "nothing unsaved: the first window")

        openEditor(sessionsA[0], text = "")
        openEditor(sessionsB[0])

        assertSame(workspaceB, WorkspaceManager.workspaceForPrompt(), "a clean editor does not count")
    }

    @Test
    fun `a quit with an unsaved editor asks in that editor's window and either answer ends the quit`() {
        val (_, sessionsA) = window(tabs = 1)
        val (workspaceB, sessionsB) = window(tabs = 1)
        openEditor(sessionsA[0], text = "")
        val editor = openEditor(sessionsB[0])
        val log = mutableListOf<String>()
        fun requestQuit() = QuitGuard.onQuitRequested(
            YamlEditRegistry.Default,
            show = WorkspaceManager::showQuitPrompt,
            perform = { log += "perform" },
            cancel = { log += "cancel" },
        )

        requestQuit()

        val prompt = assertNotNull(workspaceB.discardPrompt.value)
        assertEquals(GuardVerb.Quit, prompt.verb)
        assertEquals(listOf(editor.id), prompt.edits.map { it.windowId })
        assertEquals(emptyList(), log, "nothing is answered until the person answers")

        prompt.onKeepEditing()
        assertEquals(listOf("cancel"), log)
        assertNull(workspaceB.discardPrompt.value)

        requestQuit()
        assertNotNull(workspaceB.discardPrompt.value).onDiscard()
        assertEquals(listOf("cancel", "perform"), log, "the first answer cleared the guard's pending flag, so the second request asked again")
        assertEquals(emptyList(), openEditors(), "quitting closed every editor")
    }

    @Test
    fun `a quit prompt that no window can show is answered keep-editing`() {
        val log = mutableListOf<String>()
        val prompt = DiscardPrompt(GuardVerb.Quit, emptyList(), onDiscard = { log += "discard" }, onKeepEditing = { log += "keep" })

        WorkspaceManager.showQuitPromptIn(null, prompt)

        assertEquals(listOf("keep"), log, "the quit is cancelled, not left waiting for a prompt nobody sees")
    }

    @Test
    fun `closing a window that holds a pending quit prompt cancels the quit`() {
        val (workspace, sessions) = window(tabs = 1)
        openEditor(sessions[0])
        val log = mutableListOf<String>()
        QuitGuard.onQuitRequested(
            YamlEditRegistry.Default,
            show = { WorkspaceManager.showQuitPromptIn(workspace, it) },
            perform = { log += "perform" },
            cancel = { log += "cancel" },
        )
        assertNotNull(workspace.discardPrompt.value)

        WorkspaceManager.closeWorkspace(workspace.id)

        assertEquals(listOf("cancel"), log)
        assertNull(workspace.discardPrompt.value)
    }

    @Test
    fun `only a current-view pick of another cluster replaces a live cluster`() {
        assertTrue(replacesLiveCluster("cluster-a", "cluster-b", OpenTarget.CURRENT_VIEW))
        assertFalse(replacesLiveCluster("cluster-a", "cluster-a", OpenTarget.CURRENT_VIEW), "a re-pick of the same cluster keeps the editors")
        assertFalse(replacesLiveCluster("cluster-a", "cluster-b", OpenTarget.NEW_TAB))
        assertFalse(replacesLiveCluster("cluster-a", "cluster-b", OpenTarget.NEW_WINDOW))
        assertFalse(replacesLiveCluster(null, "cluster-b", OpenTarget.CURRENT_VIEW), "no active cluster tab: nothing to replace")
        assertFalse(replacesLiveCluster("", "cluster-b", OpenTarget.CURRENT_VIEW), "a tab that never connected has no editors")
    }
}
