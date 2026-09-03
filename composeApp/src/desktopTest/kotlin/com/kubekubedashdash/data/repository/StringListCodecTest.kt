package com.kubekubedashdash.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the palette-recents blob codec: round trip, fail-open on garbage. */
class StringListCodecTest {

    @Test
    fun `round trips a list`() {
        val list = listOf("screen:Pods", "ns:example-ns", "pod:example-ns/pod-1")
        assertEquals(list, StringListCodec.decode(StringListCodec.encode(list)))
    }

    @Test
    fun `round trips an empty list`() {
        assertEquals(emptyList(), StringListCodec.decode(StringListCodec.encode(emptyList())))
    }

    @Test
    fun `blank input decodes to an empty list`() {
        assertTrue(StringListCodec.decode(null).isEmpty())
        assertTrue(StringListCodec.decode("").isEmpty())
        assertTrue(StringListCodec.decode("   ").isEmpty())
    }

    @Test
    fun `malformed input decodes to an empty list`() {
        assertTrue(StringListCodec.decode("[not json").isEmpty())
        assertTrue(StringListCodec.decode("""{"a":"b"}""").isEmpty())
        assertTrue(StringListCodec.decode("42").isEmpty())
    }
}
