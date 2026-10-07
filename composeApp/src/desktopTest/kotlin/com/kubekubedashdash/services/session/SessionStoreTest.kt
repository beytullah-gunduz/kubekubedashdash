package com.kubekubedashdash.services.session

import com.kubekubedashdash.model.WindowGeometry
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStoreTest {

    private val tempDir = createTempDirectory("session-store-test-")
    private val file: Path = tempDir.resolve("session.json")

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `load on a missing file returns null`() {
        assertNull(SessionStore(file).load())
    }

    @Test
    fun `save then load round-trips a two-window snapshot with geometry and a CustomResource tab`() {
        val snapshot = SessionSnapshot(
            workspaces = listOf(
                SavedWorkspace(
                    tabs = listOf(
                        SavedClusterTab(
                            context = "example-context",
                            namespace = "default",
                            screen = SavedScreen(
                                ScreenCodec.OVERVIEW_KEY,
                            ),
                            paneWidthDp = 900f,
                        ),
                    ),
                    activeTab = 0,
                    geometry = WindowGeometry(x = 10, y = 20, widthDp = 1440, heightDp = 960),
                ),
                SavedWorkspace(
                    tabs = listOf(
                        SavedClusterTab(
                            context = "example-cluster",
                            namespace = "kube-system",
                            screen = SavedScreen(
                                "CustomResource",
                                SavedCrd("group.example.com", "v1", "Widget", "widgets", true),
                            ),
                            paneWidthDp = 640f,
                        ),
                    ),
                    activeTab = -1,
                    geometry = null,
                ),
            ),
        )

        val store = SessionStore(file)
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

    @Test
    fun `malformed content decodes to null`() {
        file.writeText("{not json")
        assertNull(SessionStore(file).load())
    }

    @Test
    fun `a file with an unsupported schema version decodes to null`() {
        file.writeText("""{"version": 99, "workspaces": []}""")
        assertNull(SessionStore(file).load())
    }

    @Test
    fun `after save no tmp sibling remains and saving twice leaves the second snapshot`() {
        val store = SessionStore(file)
        val first = SessionSnapshot(workspaces = emptyList())
        val second = SessionSnapshot(
            workspaces = listOf(
                SavedWorkspace(
                    tabs = listOf(SavedClusterTab(context = "demo-cluster (mock) #2")),
                    activeTab = 0,
                ),
            ),
        )

        store.save(first)
        assertFalse(tempDir.resolve("session.json.tmp").exists())

        store.save(second)
        assertFalse(tempDir.resolve("session.json.tmp").exists())
        assertEquals(second, store.load())
    }

    @Test
    fun `a multi-namespace tab round-trips and keeps all namespaces for older builds`() {
        val snapshot = SessionSnapshot(
            workspaces = listOf(
                SavedWorkspace(
                    tabs = listOf(
                        SavedClusterTab(
                            context = "example-context",
                            namespace = "All Namespaces",
                            namespaces = listOf("ns-a", "ns-b"),
                        ),
                    ),
                    activeTab = 0,
                ),
            ),
        )

        val store = SessionStore(file)
        store.save(snapshot)

        assertEquals(snapshot, store.load())
        val text = file.readText()
        assertTrue(text.contains("\"namespaces\""), text)
        assertTrue(text.contains("\"namespace\": \"All Namespaces\""), text)
    }

    @Test
    fun `a file saved before the namespaces field still loads`() {
        file.writeText(
            """{"version":1,"workspaces":[{"tabs":[{"context":"example-context","namespace":"default","paneWidthDp":800.0}],"activeTab":0}]}""",
        )
        val tab = SessionStore(file).load()!!.workspaces[0].tabs[0]
        assertNull(tab.namespaces)
        assertEquals("default", tab.namespace)
    }

    @Test
    fun `a file saved before the width became optional still loads`() {
        file.writeText(
            """{"version":1,"workspaces":[{"tabs":[{"context":"example-context","namespace":"default","paneWidthDp":800.0}],"activeTab":0}]}""",
        )
        val tab = SessionStore(file).load()!!.workspaces[0].tabs[0]
        assertEquals(800f, tab.paneWidthDp)
    }
}
