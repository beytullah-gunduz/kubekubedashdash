package com.kubekubedashdash.util

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoContextScreenshotLabelTest {

    @AfterTest
    fun resetLabels() {
        DemoContext.screenshotLabels = emptyList()
    }

    @Test
    fun `a listed label is a mock context and its own preference key`() {
        DemoContext.screenshotLabels = listOf("shot-alpha", "shot-beta")

        assertTrue(DemoContext.isScreenshotLabel("shot-alpha"))
        assertTrue(DemoContext.isMockContext("shot-alpha"))
        assertTrue(DemoContext.isMockContext("shot-beta"))
        assertEquals("shot-alpha", DemoContext.preferenceKey("shot-alpha"))
        assertEquals("shot-beta", DemoContext.preferenceKey("shot-beta"))
    }

    @Test
    fun `an unlisted plain name is not a mock context`() {
        DemoContext.screenshotLabels = listOf("shot-alpha")

        assertFalse(DemoContext.isScreenshotLabel("shot-gamma"))
        assertFalse(DemoContext.isMockContext("shot-gamma"))
        assertEquals("shot-gamma", DemoContext.preferenceKey("shot-gamma"))
    }

    @Test
    fun `with no labels set a plain name is not a mock context`() {
        assertFalse(DemoContext.isScreenshotLabel("shot-alpha"))
        assertFalse(DemoContext.isMockContext("shot-alpha"))
    }

    @Test
    fun `a minted demo label still folds to the bare name while labels are set`() {
        DemoContext.screenshotLabels = listOf("shot-alpha")

        assertTrue(DemoContext.isMockContext("demo-cluster (mock) #2"))
        assertFalse(DemoContext.isScreenshotLabel("demo-cluster (mock) #2"))
        assertEquals(DemoContext.MOCK_CONTEXT_NAME, DemoContext.preferenceKey("demo-cluster (mock) #2"))
    }

    @Test
    fun `the bare mock name keeps its own key while labels are set`() {
        DemoContext.screenshotLabels = listOf("shot-alpha")

        assertTrue(DemoContext.isMockContext(DemoContext.MOCK_CONTEXT_NAME))
        assertEquals(DemoContext.MOCK_CONTEXT_NAME, DemoContext.preferenceKey(DemoContext.MOCK_CONTEXT_NAME))
    }
}
