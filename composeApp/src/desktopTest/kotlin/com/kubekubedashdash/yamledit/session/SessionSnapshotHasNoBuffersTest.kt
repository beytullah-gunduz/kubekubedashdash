package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.services.session.SessionSnapshotBuilder
import com.kubekubedashdash.services.session.SessionStore
import com.kubekubedashdash.services.session.TabView
import com.kubekubedashdash.services.session.WorkspaceView
import com.kubekubedashdash.yamledit.TEST_NAMESPACE
import com.kubekubedashdash.yamledit.WriterMock
import com.kubekubedashdash.yamledit.YamlWriter
import com.kubekubedashdash.yamledit.configMapTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D11: session restore never persists editors. A schema pin, because the snapshot builder takes
 * only window views (which an editor registry cannot reach) and the store's JSON settings are
 * private: with unsaved text open in both kinds of editor, a snapshot of the same cluster tab,
 * written through a real [SessionStore] to a temporary file, carries none of it, and its fields are
 * exactly the ones known to hold no editor text. Never touches the user's own session file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSnapshotHasNoBuffersTest {

    private val context = "cluster-a"
    private lateinit var mock: WriterMock
    private val tempDir = createTempDirectory("session-snapshot-test-")

    @BeforeTest
    fun setUp() {
        mock = WriterMock(context)
        mock.seedConfigMap()
    }

    @AfterTest
    fun tearDown() {
        mock.stop("SessionSnapshotHasNoBuffersTest")
        tempDir.toFile().deleteRecursively()
    }

    private fun keysOf(element: JsonElement): Set<String> = when (element) {
        is JsonObject -> element.keys + element.values.flatMap { keysOf(it) }
        is JsonArray -> element.flatMap { keysOf(it) }.toSet()
        else -> emptySet()
    }

    @Test
    fun `a saved session file carries no editor text and no editor field`() = runTest {
        val registry = testRegistry()
        val editBuffer = StringEditorBuffer()
        val edit = registry.openEdit(SessionId("tab-1"), context, configMapTarget(), YamlWriter(mock.manager), RecordingFeedback(), { false }, { editBuffer }, { backgroundScope })
        runCurrent()
        val editSentinel = "# sentinel-edit-buffer-7f3a91\n"
        val applySentinel = "# sentinel-apply-buffer-c20b64\n"
        editBuffer.type(editSentinel)
        val apply = openApplyEditor(registry, "tab-1", text = "kind: ConfigMap\n$applySentinel")
        assertTrue(editSentinel in edit.buffer.text() && applySentinel in apply.buffer.text(), "positive control: the sentinels are in the editors")
        assertEquals(2, registry.dirty().size, "both editors hold unsaved text while the snapshot is taken")
        val view = WorkspaceView(
            tabs = listOf(TabView(context = context, namespaceScope = NamespaceScope.single(TEST_NAMESPACE), screen = Screen.Main.ConfigMaps, paneWidthDp = 640f)),
            activeTabIndex = 0,
            geometry = null,
        )

        val snapshot = SessionSnapshotBuilder.build(listOf(view))
        val file = tempDir.resolve("session.json")
        SessionStore(file).save(snapshot)

        val written = file.readText()
        assertTrue(context in written && "ConfigMaps" in written, "positive control: this is the snapshot of the tab that has the editors")
        for (sentinel in listOf(editSentinel.trim(), applySentinel.trim(), "sentinel-edit-buffer", "sentinel-apply-buffer", "kind: ConfigMap")) {
            assertFalse(sentinel in written, "editor text '$sentinel' reached the session file")
        }
        assertEquals(
            setOf("version", "workspaces", "tabs", "activeTab", "geometry", "context", "namespace", "namespaces", "screen", "key", "crd", "paneWidthDp"),
            keysOf(Json.parseToJsonElement(written)),
            "the session file's fields are pinned: if you add one, make sure it can never hold editor text, then update this list",
        )
        assertEquals(snapshot, SessionStore(file).load(), "and the file reads back as the snapshot that was saved")

        // Negative control: text that does reach a snapshot field is found by the same check.
        val leaking = TabView(context = "sentinel-leak-4d81e2", namespaceScope = NamespaceScope.All, screen = Screen.Main.ConfigMaps, paneWidthDp = null)
        val leakFile = tempDir.resolve("leaking-session.json")
        SessionStore(leakFile).save(SessionSnapshotBuilder.build(listOf(WorkspaceView(listOf(leaking), 0, null))))
        assertTrue("sentinel-leak-4d81e2" in leakFile.readText(), "the file check can see a value that is in the snapshot")
    }
}
