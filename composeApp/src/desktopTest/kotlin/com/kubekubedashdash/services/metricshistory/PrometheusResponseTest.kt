package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.services.metricshistory.PrometheusResponse.Parsed
import com.kubekubedashdash.util.ShellEnvironment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading the Prometheus HTTP API's JSON: what is kept, what is dropped and what counts as unreadable. */
class PrometheusResponseTest {

    private var previousCloudClis: String? = null

    @BeforeTest
    fun disableCloudClis() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun restoreCloudClis() {
        val previous = previousCloudClis
        if (previous == null) {
            System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        } else {
            System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
        }
    }

    private fun matrix(vararg results: String) = """{"status":"success","data":{"resultType":"matrix","result":[${results.joinToString(",")}]}}"""

    private fun vector(vararg results: String) = """{"status":"success","data":{"resultType":"vector","result":[${results.joinToString(",")}]}}"""

    @Test
    fun `a matrix is sorted by name with non-finite samples dropped and float timestamps truncated`() {
        val body = matrix(
            """{"metric":{"container":"sidecar"},"values":[[1000.5,"1"],[1030,"NaN"],[1060,"+Inf"],[1090,"3.5"]]}""",
            """{"metric":{"container":"app"},"values":[[1000,"2"],[1030,"-Inf"]]}""",
        )

        val parsed = PrometheusResponse.parseMatrix(body, nameLabel = "container")

        assertEquals(
            Parsed.Matrix(
                listOf(
                    Series("app", listOf(SeriesPoint(1000, 2.0))),
                    Series("sidecar", listOf(SeriesPoint(1000, 1.0), SeriesPoint(1090, 3.5))),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun `a series left with no finite sample is dropped`() {
        val body = matrix(
            """{"metric":{"container":"gone"},"values":[[1000,"NaN"],[1030,"+Inf"]]}""",
            """{"metric":{"container":"app"},"values":[[1000,"7"]]}""",
        )

        assertEquals(
            Parsed.Matrix(listOf(Series("app", listOf(SeriesPoint(1000, 7.0))))),
            PrometheusResponse.parseMatrix(body, nameLabel = "container"),
        )
    }

    @Test
    fun `without a name label every series is named by the empty string`() {
        val body = matrix("""{"metric":{"container":"app"},"values":[[1000,"5"]]}""")

        assertEquals(
            Parsed.Matrix(listOf(Series("", listOf(SeriesPoint(1000, 5.0))))),
            PrometheusResponse.parseMatrix(body, nameLabel = null),
        )
    }

    @Test
    fun `a series missing the name label is named by the empty string`() {
        val body = matrix("""{"metric":{},"values":[[1000,"5"]]}""")

        assertEquals(
            Parsed.Matrix(listOf(Series("", listOf(SeriesPoint(1000, 5.0))))),
            PrometheusResponse.parseMatrix(body, nameLabel = "container"),
        )
    }

    @Test
    fun `an empty matrix is an empty matrix, not an error`() {
        assertEquals(Parsed.Matrix(emptyList()), PrometheusResponse.parseMatrix(matrix(), nameLabel = "container"))
    }

    @Test
    fun `an error envelope carries its error type`() {
        val body = """{"status":"error","errorType":"bad_data","error":"x"}"""

        assertEquals(Parsed.Error("bad_data"), PrometheusResponse.parseMatrix(body, nameLabel = "container"))
        assertEquals(Parsed.Error("bad_data"), PrometheusResponse.parseVectorFirstValue(body))
    }

    @Test
    fun `an error envelope without a type is unknown`() {
        assertEquals(Parsed.Error("unknown"), PrometheusResponse.parseMatrix("""{"status":"error"}""", nameLabel = null))
    }

    @Test
    fun `text that is not json is malformed`() {
        assertEquals(Parsed.Malformed, PrometheusResponse.parseMatrix("not json", nameLabel = null))
        assertEquals(Parsed.Malformed, PrometheusResponse.parseVectorFirstValue("not json"))
        assertEquals(Parsed.Malformed, PrometheusResponse.parseMatrix("", nameLabel = null))
    }

    @Test
    fun `a vector where a matrix is expected is malformed, and the other way round`() {
        assertEquals(Parsed.Malformed, PrometheusResponse.parseMatrix(vector(), nameLabel = null))
        assertEquals(Parsed.Malformed, PrometheusResponse.parseVectorFirstValue(matrix()))
    }

    @Test
    fun `a body of the wrong shape is malformed`() {
        for (body in listOf("[]", "{}", """{"status":"success"}""", """{"status":"success","data":[]}""", """{"status":"weird","data":{}}""")) {
            assertEquals(Parsed.Malformed, PrometheusResponse.parseMatrix(body, nameLabel = null), body)
            assertEquals(Parsed.Malformed, PrometheusResponse.parseVectorFirstValue(body), body)
        }
    }

    @Test
    fun `a sample that is not a timestamp and a value is malformed`() {
        assertEquals(Parsed.Malformed, PrometheusResponse.parseMatrix(matrix("""{"metric":{},"values":[[1000]]}"""), nameLabel = null))
        assertEquals(Parsed.Malformed, PrometheusResponse.parseMatrix(matrix("""{"metric":{},"values":[["soon","1"]]}"""), nameLabel = null))
    }

    @Test
    fun `an empty vector has no first value`() {
        assertEquals(Parsed.Scalar(null), PrometheusResponse.parseVectorFirstValue(vector()))
    }

    @Test
    fun `a vector's first sample is its value`() {
        val body = vector("""{"metric":{},"value":[1000,"42"]}""", """{"metric":{},"value":[1000,"7"]}""")

        assertEquals(Parsed.Scalar(42.0), PrometheusResponse.parseVectorFirstValue(body))
    }

    @Test
    fun `an unparsable vector value is a scalar without a value`() {
        assertEquals(Parsed.Scalar(null), PrometheusResponse.parseVectorFirstValue(vector("""{"metric":{},"value":[1000,"many"]}""")))
    }

    @Test
    fun `errorTypeOf reads an envelope and nothing else`() {
        assertEquals("bad_data", PrometheusResponse.errorTypeOf("""{"status":"error","errorType":"bad_data","error":"x"}"""))
        assertEquals("unknown", PrometheusResponse.errorTypeOf("""{"status":"error"}"""))
        assertNull(PrometheusResponse.errorTypeOf(matrix()))
        assertNull(PrometheusResponse.errorTypeOf("not json"))
        assertNull(PrometheusResponse.errorTypeOf("[]"))
        assertNull(PrometheusResponse.errorTypeOf(""))
    }

    @Test
    fun `a success with a scalar or a vector is a queryable probe answer`() {
        assertTrue(PrometheusResponse.isQueryableProbe("""{"status":"success","data":{"resultType":"scalar","result":[1700000000,"1"]}}"""))
        assertTrue(PrometheusResponse.isQueryableProbe(vector("""{"metric":{},"value":[1700000000,"1"]}""")))
        assertTrue(PrometheusResponse.isQueryableProbe(vector()))
    }

    @Test
    fun `anything else is not a queryable probe answer`() {
        for (body in listOf(
            matrix(),
            """{"status":"success","data":{"resultType":"string","result":[1,"x"]}}""",
            """{"status":"error","errorType":"execution","error":"unavailable with Prometheus Agent"}""",
            """{"status":"success"}""",
            """{"status":"success","data":[]}""",
            """{"status":"success","data":{"resultType":7}}""",
            """{"status":"success","data":{"resultType":null}}""",
            """{"status":"fail","data":{"resultType":"scalar"}}""",
            """{"kind":"Status","status":"Failure","code":503}""",
            "<html>login</html>",
            "[]",
            "",
        )) {
            assertFalse(PrometheusResponse.isQueryableProbe(body), body)
        }
    }
}
