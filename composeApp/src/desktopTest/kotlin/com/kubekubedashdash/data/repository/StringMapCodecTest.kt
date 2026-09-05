package com.kubekubedashdash.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class StringMapCodecTest {

    @Test
    fun `null and blank decode to empty`() {
        assertEquals(emptyMap(), StringMapCodec.decode(null))
        assertEquals(emptyMap(), StringMapCodec.decode(""))
        assertEquals(emptyMap(), StringMapCodec.decode("   "))
    }

    @Test
    fun `malformed input decodes to empty`() {
        assertEquals(emptyMap(), StringMapCodec.decode("{not json"))
        assertEquals(emptyMap(), StringMapCodec.decode("""{"example-cluster":1}"""))
        assertEquals(emptyMap(), StringMapCodec.decode("[true]"))
    }

    @Test
    fun `round trip preserves entries`() {
        val map = mapOf("example-cluster" to "example-ns", "demo-cluster (mock)" to "kube-system")
        assertEquals(map, StringMapCodec.decode(StringMapCodec.encode(map)))
    }

    @Test
    fun `decodes a hand-written blob`() {
        assertEquals(
            mapOf("example-cluster" to "#E53935"),
            StringMapCodec.decode("""{"example-cluster":"#E53935"}"""),
        )
    }
}
