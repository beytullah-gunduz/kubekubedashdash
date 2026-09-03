package com.kubekubedashdash.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the detail-pane-width blob codec: round trip, fail-open on garbage. */
class FloatMapCodecTest {

    @Test
    fun `round trips a map`() {
        val map = mapOf("Pod" to 512.5f, "ConfigMap" to 640f)
        assertEquals(map, FloatMapCodec.decode(FloatMapCodec.encode(map)))
    }

    @Test
    fun `blank and malformed input decode to an empty map`() {
        assertTrue(FloatMapCodec.decode(null).isEmpty())
        assertTrue(FloatMapCodec.decode("").isEmpty())
        assertTrue(FloatMapCodec.decode("{not json").isEmpty())
        assertTrue(FloatMapCodec.decode("""{"Pod":"wide"}""").isEmpty())
    }
}
