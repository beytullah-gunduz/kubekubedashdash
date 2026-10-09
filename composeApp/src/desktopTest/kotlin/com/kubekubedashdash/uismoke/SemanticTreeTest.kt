package com.kubekubedashdash.uismoke

import com.kubekubedashdash.model.HistoryLocation
import com.kubekubedashdash.model.NavigationHistoryState
import com.kubekubedashdash.ui.UiTestHookNames
import com.kubekubedashdash.ui.uiTestStateJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the UI smoke: how a get_semantic_tree and a list_windows answer are read. */
class SemanticTreeTest {
    private fun node(json: String): SemNode = parseRoots(json).single()

    private val sampleTree = """
        {"id":1,"bounds":{"x":0,"y":0,"width":2880,"height":1920},"children":[
          {"id":2,"text":"Try demo cluster","actions":["onClick"],"bounds":{"x":100,"y":200,"width":300,"height":80},
           "children":[{"id":3,"text":"inner"}]},
          {"id":4,"contentDescription":"Close window","actions":["onClick"]}
        ]}
    """.trimIndent()

    // ---------------------------------------------------------------- parseRoots and flatten

    @Test
    fun `parseRoots reads one object as one root`() {
        val roots = parseRoots(sampleTree)
        assertEquals(1, roots.size)
        assertEquals(1L, roots.single().id)
        assertEquals(2, roots.single().children.size)
    }

    @Test
    fun `parseRoots reads a list as its roots in order`() {
        val roots = parseRoots("""[{"id":10},{"id":11,"children":[{"id":12}]}]""")
        assertEquals(listOf(10L, 11L), roots.map { it.id })
        assertEquals(listOf(12L), roots[1].children.map { it.id })
    }

    @Test
    fun `parseRoots skips list entries that are not objects`() {
        assertEquals(listOf(7L), parseRoots("""[1,"two",{"id":7},null]""").map { it.id })
    }

    @Test
    fun `parseRoots gives no roots for an error answer`() {
        assertTrue(parseRoots("""{"error":"x"}""").isEmpty())
    }

    @Test
    fun `parseRoots gives no roots for text that is not JSON`() {
        assertTrue(parseRoots("not json").isEmpty())
        assertTrue(parseRoots("").isEmpty())
        assertTrue(parseRoots("42").isEmpty())
    }

    @Test
    fun `a node without an id has id minus one`() {
        assertEquals(-1L, node("""{"text":"x"}""").id)
    }

    @Test
    fun `flatten keeps document order`() {
        val roots = parseRoots(
            """{"id":1,"children":[{"id":2,"children":[{"id":3}]},{"id":4}]}""",
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), flatten(roots).map { it.id })
    }

    @Test
    fun `flatten runs through every root`() {
        val roots = parseRoots("""[{"id":1,"children":[{"id":2}]},{"id":3}]""")
        assertEquals(listOf(1L, 2L, 3L), flatten(roots).map { it.id })
    }

    // ---------------------------------------------------------------- node fields

    @Test
    fun `enabled defaults to true and reads false`() {
        assertTrue(node("""{"id":1}""").enabled)
        assertTrue(node("""{"id":1,"enabled":true}""").enabled)
        assertFalse(node("""{"id":1,"enabled":false}""").enabled)
    }

    @Test
    fun `clickable needs an onClick action and an enabled node`() {
        assertTrue(node("""{"id":1,"actions":["onClick"]}""").clickable)
        assertTrue(node("""{"id":1,"actions":["onLongClick","onClick"]}""").clickable)
        assertFalse(node("""{"id":1,"actions":["onClick"],"enabled":false}""").clickable)
        assertFalse(node("""{"id":1,"actions":["scrollBy"]}""").clickable)
        assertFalse(node("""{"id":1}""").clickable)
    }

    @Test
    fun `text and contentDescription are plain strings, joined when they arrive as lists`() {
        val n = node("""{"id":1,"text":["a","b"],"contentDescription":"Close window"}""")
        assertEquals("a b", n.text)
        assertEquals("Close window", n.contentDescription)
        val bare = node("""{"id":2}""")
        assertEquals("", bare.text)
        assertEquals("", bare.contentDescription)
    }

    @Test
    fun `bounds are read in pixels and a node without bounds has none`() {
        val bounds = assertNotNull(parseRoots(sampleTree).single().bounds)
        assertEquals(Bounds(0.0, 0.0, 2880.0, 1920.0), bounds)
        assertEquals(1920.0, bounds.bottom)
        assertNull(node("""{"id":1}""").bounds)
    }

    // ---------------------------------------------------------------- hay

    @Test
    fun `hay reads a string, a list and nothing`() {
        assertEquals("abc", hay(JsonPrimitive("abc")))
        assertEquals("a b", hay(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))))
        assertEquals("", hay(null))
        assertEquals("", hay(JsonNull))
        assertEquals("", hay(JsonArray(emptyList())))
        assertEquals("5", hay(JsonPrimitive(5)))
    }

    // ---------------------------------------------------------------- matching

    private val hookNode = """{"id":9,"contentDescription":"${UiTestHookNames.BACK}","actions":["onClick"],"bounds":{"x":0,"y":0,"width":0,"height":0}}"""

    private val matchTree = parseRoots(
        """
        [{"id":1,"children":[
          {"id":2,"text":"Try demo cluster","actions":["onClick"]},
          {"id":3,"text":"Try demo cluster (disabled)","actions":["onClick"],"enabled":false},
          {"id":4,"contentDescription":"Back","actions":["onClick"]},
          {"id":5,"contentDescription":"Back","enabled":false},
          {"id":6,"text":"Open demo cluster","contentDescription":"Open","actions":["onClick"]},
          $hookNode
        ]}]
        """.trimIndent(),
    ).let(::flatten)

    @Test
    fun `matching EXACT compares the whole string`() {
        assertEquals(listOf(2L), matchTree.matching(text = "Try demo cluster").map { it.id })
        assertTrue(matchTree.matching(text = "Try demo").isEmpty())
    }

    @Test
    fun `matching PREFIX CONTAINS and SUFFIX`() {
        // The disabled node 3 is not clickable.
        assertEquals(listOf(2L), matchTree.matching(text = "Try demo", match = Match.PREFIX).map { it.id })
        assertEquals(listOf(2L, 6L), matchTree.matching(text = "demo cluster", match = Match.CONTAINS).map { it.id })
        assertEquals(listOf(6L), matchTree.matching(text = "Open demo cluster", match = Match.SUFFIX).map { it.id })
        assertEquals(listOf(2L, 6L), matchTree.matching(text = "cluster", match = Match.SUFFIX).map { it.id })
    }

    @Test
    fun `matching by contentDescription`() {
        assertEquals(listOf(4L), matchTree.matching(desc = "Back").map { it.id })
        // The hook node (id 9) is clickable too, so a prefix match finds it.
        assertEquals(listOf(9L), matchTree.matching(desc = "ui-test:", match = Match.PREFIX).map { it.id })
    }

    @Test
    fun `matching with clickable false keeps disabled and inert nodes`() {
        assertEquals(listOf(4L, 5L), matchTree.matching(desc = "Back", clickable = false).map { it.id })
        assertEquals(listOf(2L, 3L), matchTree.matching(text = "Try demo", match = Match.PREFIX, clickable = false).map { it.id })
    }

    @Test
    fun `matching desc and text must both hold`() {
        assertEquals(listOf(6L), matchTree.matching(desc = "Open", text = "Open demo cluster").map { it.id })
        assertTrue(matchTree.matching(desc = "Back", text = "Open demo cluster").isEmpty())
    }

    @Test
    fun `matching finds a hook node that is 0x0`() {
        val hit = matchTree.matching(desc = UiTestHookNames.BACK).single()
        assertEquals(9L, hit.id)
        assertEquals(0.0, hit.bounds?.width)
        assertEquals(0.0, hit.bounds?.height)
    }

    @Test
    fun `matching needs a desc or a text`() {
        assertFailsWith<IllegalArgumentException> { matchTree.matching() }
    }

    @Test
    fun `Match tests each way`() {
        assertTrue(Match.EXACT.test("abc", "abc"))
        assertFalse(Match.EXACT.test("abcd", "abc"))
        assertTrue(Match.PREFIX.test("abcd", "abc"))
        assertFalse(Match.PREFIX.test("xabc", "abc"))
        assertTrue(Match.CONTAINS.test("xabcx", "abc"))
        assertFalse(Match.CONTAINS.test("ab", "abc"))
        assertTrue(Match.SUFFIX.test("xabc", "abc"))
        assertFalse(Match.SUFFIX.test("abcx", "abc"))
    }

    // ---------------------------------------------------------------- the state hook

    private fun stateJson(tabs: List<String>, active: String?) = uiTestStateJson(
        workspaceId = "window-1",
        activeTabKey = active,
        pagerPageKey = active,
        tabKeys = tabs,
        history = NavigationHistoryState(
            back = listOf(HistoryLocation(tabKey = "cluster:example-context-01")),
            forward = listOf(HistoryLocation(tabKey = "cluster:example-context-02")),
        ),
        firstRun = false,
        screenTitle = "Cluster Overview",
        paneOpen = true,
        lastShortcut = "Cmd+[",
    )

    private fun stateTree(description: String): List<SemNode> {
        val state = buildJsonObject {
            put("id", 5)
            put("contentDescription", description)
        }
        return flatten(parseRoots("""{"id":1,"children":[$state]}"""))
    }

    @Test
    fun `uiTestState decodes what the app encodes`() {
        val tabs = listOf("cluster:example-context-01", "cluster:example-context-02")
        val state = assertNotNull(stateTree(UiTestHookNames.STATE + stateJson(tabs, "cluster:example-context-02")).uiTestState())
        assertEquals("window-1", state.window)
        assertEquals("cluster:example-context-02", state.active)
        assertEquals("cluster:example-context-02", state.page)
        assertEquals(tabs, state.tabs)
        assertEquals(listOf("cluster:example-context-01"), state.back)
        assertEquals(listOf("cluster:example-context-02"), state.forward)
        assertFalse(state.firstRun)
        assertEquals("Cluster Overview", state.screen)
        assertTrue(state.pane)
        assertEquals("Cmd+[", state.lastShortcut)
    }

    @Test
    fun `uiTestState keeps a missing active tab as null`() {
        val state = assertNotNull(stateTree(UiTestHookNames.STATE + stateJson(emptyList(), null)).uiTestState())
        assertNull(state.active)
        assertNull(state.page)
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun `uiTestState ignores keys it does not know`() {
        val json = stateJson(listOf("cluster:example-context-01"), "cluster:example-context-01").removeSuffix("}") + ""","extra":1}"""
        assertNotNull(stateTree(UiTestHookNames.STATE + json).uiTestState())
    }

    @Test
    fun `uiTestState is null for a garbled state`() {
        assertNull(stateTree(UiTestHookNames.STATE + "{not json").uiTestState())
        assertNull(stateTree(UiTestHookNames.STATE).uiTestState())
        // Valid JSON, but the wrong shape.
        assertNull(stateTree(UiTestHookNames.STATE + """{"window":"w"}""").uiTestState())
    }

    @Test
    fun `uiTestState is null when there is no state node`() {
        assertNull(flatten(parseRoots(sampleTree)).uiTestState())
        assertNull(emptyList<SemNode>().uiTestState())
    }

    // ---------------------------------------------------------------- parseWindows

    private val oneWindow = """{"id":"11111111-aaaa-bbbb-cccc-000000000001","title":"Example","x":0,"y":0,"width":1440,"height":960}"""

    @Test
    fun `parseWindows reads a list`() {
        val windows = parseWindows("[$oneWindow,{\"id\":\"w2\",\"title\":\"Second\",\"width\":800.5,\"height\":600}]")
        assertEquals(
            listOf(
                McpWindow("11111111-aaaa-bbbb-cccc-000000000001", "Example", 1440.0, 960.0),
                McpWindow("w2", "Second", 800.5, 600.0),
            ),
            windows,
        )
    }

    @Test
    fun `parseWindows reads a windows object`() {
        assertEquals(listOf("w1"), parseWindows("""{"windows":[{"id":"w1"}]}""").map { it.id })
        assertEquals(McpWindow("w1", "", 0.0, 0.0), parseWindows("""{"windows":[{"id":"w1"}]}""").single())
    }

    @Test
    fun `parseWindows skips entries without an id`() {
        assertEquals(listOf("w1"), parseWindows("""[{"title":"no id"},3,{"id":"w1"}]""").map { it.id })
    }

    @Test
    fun `parseWindows gives nothing for junk`() {
        assertTrue(parseWindows("not json").isEmpty())
        assertTrue(parseWindows("").isEmpty())
        assertTrue(parseWindows("""{"error":"no app"}""").isEmpty())
        assertTrue(parseWindows("""{"windows":"none"}""").isEmpty())
        assertTrue(parseWindows("7").isEmpty())
        assertTrue(parseWindows("[]").isEmpty())
    }
}
