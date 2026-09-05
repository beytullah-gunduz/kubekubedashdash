package com.kubekubedashdash.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DemoContextTest {

    @Test
    fun `preferenceKey folds a minted mock label back to the bare name`() {
        assertEquals("demo-cluster (mock)", DemoContext.preferenceKey("demo-cluster (mock) #3"))
    }

    @Test
    fun `preferenceKey is a no-op for a non-mock context`() {
        assertEquals("example-cluster", DemoContext.preferenceKey("example-cluster"))
    }

    @Test
    fun `preferenceKey is a no-op for the bare mock name`() {
        assertEquals("demo-cluster (mock)", DemoContext.preferenceKey("demo-cluster (mock)"))
    }
}
