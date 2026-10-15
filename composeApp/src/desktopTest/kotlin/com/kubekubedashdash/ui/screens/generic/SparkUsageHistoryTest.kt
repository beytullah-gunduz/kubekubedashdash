package com.kubekubedashdash.ui.screens.generic

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.SparkRun
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SparkUsageHistoryTest {

    private fun app(name: String, namespace: String? = "production", driver: String = "$name-driver", executors: List<String> = listOf("$name-exec-1", "$name-exec-2"), withSpark: Boolean = true) = GenericResourceInfo(
        uid = "uid-$namespace-$name",
        name = name,
        namespace = namespace,
        status = null,
        age = "1h",
        labels = emptyMap(),
        annotations = emptyMap(),
        spark = if (withSpark) SparkRun(driver, executors, "", "", "RUNNING") else null,
    )

    private fun pod(name: String, vararg values: Double, namespace: String = "production") = Series("$namespace/$name", values.mapIndexed { i, v -> SeriesPoint(100L + 30 * i, v) })

    private fun names(lines: List<Series>) = lines.map { it.name }

    @Test
    fun `sumPointwise merges timestamps and sorts them`() {
        val merged = sumPointwise(
            listOf(
                listOf(SeriesPoint(130, 2.0), SeriesPoint(100, 1.0)),
                listOf(SeriesPoint(130, 3.0), SeriesPoint(160, 4.0)),
            ),
        )

        assertEquals(listOf(SeriesPoint(100, 1.0), SeriesPoint(130, 5.0), SeriesPoint(160, 4.0)), merged)
        assertEquals(emptyList(), sumPointwise(emptyList()))
    }

    @Test
    fun `a driver and its executors sum into the line of their application`() {
        val lines = sparkApplicationLines(
            listOf(pod("a-driver", 1.0, 1.0), pod("a-exec-1", 2.0, 3.0), pod("a-exec-2", 4.0, 5.0), pod("b-driver", 1.0, 1.0)),
            listOf(app("a"), app("b")),
        )

        assertEquals(listOf("a", "b"), names(lines))
        assertEquals(listOf(SeriesPoint(100, 7.0), SeriesPoint(130, 9.0)), lines[0].points)
        assertEquals(listOf(SeriesPoint(100, 1.0), SeriesPoint(130, 1.0)), lines[1].points)
    }

    @Test
    fun `the same pod name in another namespace is not attributed`() {
        val lines = sparkApplicationLines(
            listOf(pod("a-driver", 1.0), pod("a-driver", 10.0, namespace = "team-b")),
            listOf(app("a", namespace = "production")),
        )

        assertEquals(listOf("a", SPARK_OTHER_SERIES_NAME), names(lines))
        assertEquals(1.0, lines[0].points.single().value)
        assertEquals(10.0, lines[1].points.single().value)
    }

    @Test
    fun `lines are ranked by the sum of their points, largest first`() {
        // "small" peaks higher but "wide" has more total.
        val lines = sparkApplicationLines(
            listOf(pod("small-driver", 9.0), pod("wide-driver", 4.0, 4.0, 4.0)),
            listOf(app("small"), app("wide")),
        )

        assertEquals(listOf("wide", "small"), names(lines))
    }

    @Test
    fun `equal sums tie-break by label`() {
        val lines = sparkApplicationLines(
            listOf(pod("zeta-driver", 2.0), pod("alpha-driver", 2.0), pod("mid-driver", 2.0)),
            listOf(app("zeta"), app("alpha"), app("mid")),
        )

        assertEquals(listOf("alpha", "mid", "zeta"), names(lines))
    }

    @Test
    fun `seven applications give the five largest and Other with the rest and the unlisted pods`() {
        val apps = (1..7).map { app("app$it") }
        val series = (1..7).map { pod("app$it-driver", it.toDouble()) } + pod("deleted-driver", 100.0)

        val lines = sparkApplicationLines(series, apps)

        assertEquals(listOf("app7", "app6", "app5", "app4", "app3", SPARK_OTHER_SERIES_NAME), names(lines))
        // app2 (2) + app1 (1) + the unlisted pod (100).
        assertEquals(103.0, lines.last().points.single().value)
    }

    @Test
    fun `Other is absent when every pod is attributed`() {
        val lines = sparkApplicationLines(listOf(pod("a-driver", 1.0), pod("a-exec-1", 1.0)), listOf(app("a")))

        assertEquals(listOf("a"), names(lines))
    }

    @Test
    fun `with no pod attributed only Other remains`() {
        val lines = sparkApplicationLines(listOf(pod("gone-driver", 1.0), pod("gone-exec-1", 2.0)), listOf(app("a")))

        assertEquals(listOf(SPARK_OTHER_SERIES_NAME), names(lines))
        assertEquals(3.0, lines.single().points.single().value)
    }

    @Test
    fun `an application without a namespace or spark data gets no line and its pods go to Other`() {
        val lines = sparkApplicationLines(
            listOf(pod("a-driver", 1.0), pod("b-driver", 2.0)),
            listOf(app("a", namespace = null), app("b", withSpark = false)),
        )

        assertEquals(listOf(SPARK_OTHER_SERIES_NAME), names(lines))
    }

    @Test
    fun `an application none of whose series has a point gets no line`() {
        val lines = sparkApplicationLines(listOf(Series("production/a-driver", emptyList())), listOf(app("a")))

        assertEquals(emptyList(), lines)
    }

    @Test
    fun `a pod named by two applications goes to the first of them`() {
        val lines = sparkApplicationLines(
            listOf(pod("shared-driver", 5.0)),
            listOf(app("first", driver = "shared-driver"), app("second", driver = "shared-driver")),
        )

        assertEquals(listOf("first"), names(lines))
    }

    @Test
    fun `two applications sharing a name are labelled with their namespace`() {
        val lines = sparkApplicationLines(
            listOf(pod("etl-driver", 3.0, namespace = "team-a"), pod("etl-driver", 2.0, namespace = "team-b"), pod("solo-driver", 1.0)),
            listOf(app("etl", namespace = "team-a"), app("etl", namespace = "team-b"), app("solo")),
        )

        assertEquals(listOf("etl (team-a)", "etl (team-b)", "solo"), names(lines))
    }

    @Test
    fun `an application named Other shares the label of the Other line`() {
        val lines = sparkApplicationLines(listOf(pod("Other-driver", 2.0), pod("gone-driver", 1.0)), listOf(app("Other")))

        assertEquals(listOf(SPARK_OTHER_SERIES_NAME, SPARK_OTHER_SERIES_NAME), names(lines))
    }

    @Test
    fun `an application keeps one colour index when it ranks differently on CPU and memory`() {
        val apps = listOf(app("a"), app("b"), app("c"))
        val cpu = sparkApplicationLines(listOf(pod("a-driver", 3.0), pod("b-driver", 2.0), pod("c-driver", 1.0), pod("gone-driver", 1.0)), apps)
        val memory = sparkApplicationLines(listOf(pod("c-driver", 3.0), pod("b-driver", 2.0), pod("a-driver", 1.0)), apps)

        val index = sparkColorIndex(cpu, memory)

        assertEquals(listOf("a", "b", "c"), cpu.map { it.name }.filter { it != SPARK_OTHER_SERIES_NAME })
        assertEquals(listOf("c", "b", "a"), names(memory))
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2, SPARK_OTHER_SERIES_NAME to 3), index)
    }

    @Test
    fun `memory-only labels follow the CPU ones and Other comes after them all`() {
        val cpu = listOf(Series("a", emptyList()), Series(SPARK_OTHER_SERIES_NAME, emptyList()))
        val memory = listOf(Series("z", emptyList()), Series("a", emptyList()))

        val index = sparkColorIndex(cpu, memory)

        assertEquals(0, index["a"])
        assertEquals(1, index["z"])
        assertEquals(2, index[SPARK_OTHER_SERIES_NAME])
        assertNotEquals(index["a"], index["z"])
        assertTrue(index.values.toSet().size == index.size)
    }
}
