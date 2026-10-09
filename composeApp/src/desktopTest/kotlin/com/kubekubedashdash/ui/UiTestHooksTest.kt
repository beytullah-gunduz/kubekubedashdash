package com.kubekubedashdash.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.kubekubedashdash.model.HistoryLocation
import com.kubekubedashdash.model.NavigationHistoryState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiTestHooksTest {
    @Test
    fun `the hooks are off unless a hot run turns them on`() {
        // The Gradle test task never sets kkdd.uiTestHooks.
        assertFalse(UiTestHooksEnabled)
    }

    @Test
    fun `the state is one line of JSON with every field`() {
        val json = uiTestStateJson(
            workspaceId = "w1",
            activeTabKey = "cluster:a",
            pagerPageKey = "cluster:a",
            tabKeys = listOf("cluster:a", "all-clusters"),
            history = NavigationHistoryState(back = listOf(HistoryLocation("cluster:a"))),
            firstRun = false,
            screenTitle = "Pods",
            paneOpen = true,
            lastShortcut = "handled",
        )
        assertFalse(json.contains('\n'))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("w1", obj.getValue("window").jsonPrimitive.content)
        assertEquals("cluster:a", obj.getValue("active").jsonPrimitive.content)
        assertEquals("cluster:a", obj.getValue("page").jsonPrimitive.content)
        assertEquals(2, obj.getValue("tabs").jsonArray.size)
        assertEquals(listOf("cluster:a"), obj.getValue("back").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(obj.getValue("forward").jsonArray.isEmpty())
        assertFalse(obj.getValue("firstRun").jsonPrimitive.boolean)
        assertEquals("Pods", obj.getValue("screen").jsonPrimitive.content)
        assertTrue(obj.getValue("pane").jsonPrimitive.boolean)
        assertEquals("handled", obj.getValue("lastShortcut").jsonPrimitive.content)
    }

    @Test
    fun `a null active tab and screen are JSON nulls`() {
        val json = uiTestStateJson("w1", null, null, emptyList(), NavigationHistoryState(), true, null, false, "none")
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(JsonNull, obj.getValue("active"))
        assertEquals(JsonNull, obj.getValue("screen"))
    }

    @Test
    fun `firstPointOutside skips points inside any drop zone`() {
        val zones = listOf(Rect(0f, 0f, 100f, 40f))
        val candidates = listOf(Offset(50f, 20f), Offset(50f, 80f))
        assertEquals(Offset(50f, 80f), firstPointOutside(candidates, zones))
    }

    @Test
    fun `firstPointOutside is null when every point is covered`() {
        val zones = listOf(Rect(0f, 0f, 100f, 100f))
        assertNull(firstPointOutside(listOf(Offset(10f, 10f)), zones))
    }

    @Test
    fun `the state decodes back into UiTestState`() {
        val json = uiTestStateJson(
            workspaceId = "w1",
            activeTabKey = null,
            pagerPageKey = "all-clusters",
            tabKeys = listOf("all-clusters"),
            history = NavigationHistoryState(forward = listOf(HistoryLocation("all-clusters"))),
            firstRun = true,
            screenTitle = null,
            paneOpen = false,
            lastShortcut = "passed",
        )
        val decoded = Json.decodeFromString(UiTestState.serializer(), json)
        assertEquals(
            UiTestState(
                window = "w1",
                active = null,
                page = "all-clusters",
                tabs = listOf("all-clusters"),
                back = emptyList(),
                forward = listOf("all-clusters"),
                firstRun = true,
                screen = null,
                pane = false,
                lastShortcut = "passed",
            ),
            decoded,
        )
    }
}
