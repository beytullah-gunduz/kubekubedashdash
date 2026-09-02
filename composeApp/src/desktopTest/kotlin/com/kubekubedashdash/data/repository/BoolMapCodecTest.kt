package com.kubekubedashdash.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class BoolMapCodecTest {

    @Test
    fun `null and blank decode to empty`() {
        assertEquals(emptyMap(), BoolMapCodec.decode(null))
        assertEquals(emptyMap(), BoolMapCodec.decode(""))
        assertEquals(emptyMap(), BoolMapCodec.decode("   "))
    }

    @Test
    fun `malformed input decodes to empty`() {
        assertEquals(emptyMap(), BoolMapCodec.decode("{not json"))
        assertEquals(emptyMap(), BoolMapCodec.decode("""{"Workloads":"yes"}"""))
        assertEquals(emptyMap(), BoolMapCodec.decode("[true]"))
    }

    @Test
    fun `round trip preserves entries`() {
        val map = mapOf("Workloads" to false, "Custom Resources" to true, "pods" to false)
        assertEquals(map, BoolMapCodec.decode(BoolMapCodec.encode(map)))
    }

    @Test
    fun `decodes a hand-written blob`() {
        assertEquals(mapOf("pods" to false, "nodes" to true), BoolMapCodec.decode("""{"pods":false,"nodes":true}"""))
    }
}
