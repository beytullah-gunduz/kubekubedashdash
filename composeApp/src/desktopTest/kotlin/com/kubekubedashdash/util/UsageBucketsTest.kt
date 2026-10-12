package com.kubekubedashdash.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic behind the usage sparklines: [bucketed] folds one session's
 * 30 s samples into 3-minute bars, [sumBuckets] adds those bars up across tabs.
 * Both are pure; the newest bar is the one holding the last sample, never the clock.
 */
class UsageBucketsTest {

    private companion object {
        // A multiple of 180, so bucket n starts at BASE + 180 * n.
        const val BASE = 1_699_999_920L
        const val BUCKET = 180L
    }

    private fun sample(epochSec: Long, cpu: Float = 0f, mem: Float = 0f, cpuUsed: Long = 0, cpuCapacity: Long = 0, memUsed: Long = 0, memCapacity: Long = 0) = UsageSample(epochSec, cpu, mem, cpuUsed, cpuCapacity, memUsed, memCapacity)

    private fun cpuAt(bucket: Int, offsetSec: Long, fraction: Float) = sample(BASE + BUCKET * bucket + offsetSec, cpu = fraction)

    private fun cpuBars(samples: List<UsageSample>) = bucketed(samples) { it.cpuFraction }

    private fun tabSample(bucket: Int, offsetSec: Long, used: Long, capacity: Long) = sample(BASE + BUCKET * bucket + offsetSec, cpuUsed = used, cpuCapacity = capacity)

    private fun sums(vararg tabs: List<UsageSample>) = sumBuckets(tabs.toList(), used = { it.cpuUsedMillis }, capacity = { it.cpuCapacityMillis })

    // ── bucketed ──────────────────────────────────────────────────────────────

    @Test
    fun `no samples, no bars`() {
        assertEquals(emptyList(), cpuBars(emptyList()))
    }

    @Test
    fun `the samples of one bucket are averaged into one bar`() {
        val bars = cpuBars(listOf(cpuAt(0, 0, 0.125f), cpuAt(0, 30, 0.25f), cpuAt(0, 60, 0.75f)))

        assertEquals(listOf(0.375f), bars)
    }

    @Test
    fun `a bucket boundary starts a new bar`() {
        // 150 s is the last slot of bucket 0, 180 s the first of bucket 1.
        val bars = cpuBars(listOf(cpuAt(0, 150, 0.2f), cpuAt(1, 0, 0.4f)))

        assertEquals(listOf(0.2f, 0.4f), bars)
    }

    @Test
    fun `buckets are aligned to the epoch, not to the first sample`() {
        // The first sample sits 170 s into its bucket: the next one, 30 s later, is already in the following bar.
        val bars = cpuBars(listOf(cpuAt(0, 170, 0.1f), cpuAt(0, 200, 0.3f)))

        assertEquals(listOf(0.1f, 0.3f), bars)
    }

    @Test
    fun `an empty bucket in the middle is skipped, not drawn as zero`() {
        val bars = cpuBars(listOf(cpuAt(0, 0, 0.2f), cpuAt(3, 0, 0.8f)))

        assertEquals(listOf(0.2f, 0.8f), bars)
    }

    @Test
    fun `only the 20 newest buckets count`() {
        val samples = (0 until 25).map { cpuAt(it, 0, it / 100f) }

        val bars = cpuBars(samples)

        assertEquals(20, bars.size)
        assertEquals(5 / 100f, bars.first())
        assertEquals(24 / 100f, bars.last())
    }

    @Test
    fun `a gap does not pull older buckets into the window`() {
        // Nine buckets, then a long quiet stretch, then one sample: the 20-bucket
        // window ends at the newest sample's bucket, so nothing older than 19 buckets back is in it.
        val samples = (0 until 9).map { cpuAt(it, 0, 0.1f) } + cpuAt(29, 0, 0.9f)

        assertEquals(listOf(0.9f), cpuBars(samples))
    }

    @Test
    fun `the window ends at the last sample's bucket`() {
        val samples = listOf(cpuAt(10, 0, 0.1f), cpuAt(11, 0, 0.2f), cpuAt(29, 0, 0.3f))

        // Slots 10 to 29 are the 20 newest: all three are in.
        assertEquals(listOf(0.1f, 0.2f, 0.3f), cpuBars(samples))
        // A sample one slot later moves the window on and slot 10 drops out.
        assertEquals(listOf(0.2f, 0.3f, 0.4f), cpuBars(samples + cpuAt(30, 0, 0.4f)))
    }

    @Test
    fun `bucket count and width are parameters`() {
        val samples = listOf(sample(100, cpu = 0.25f), sample(130, cpu = 0.5f), sample(160, cpu = 0.75f), sample(200, cpu = 1f))

        assertEquals(listOf(0.5f, 1f), bucketed(samples, buckets = 2, bucketSec = 100) { it.cpuFraction })
        assertEquals(listOf(1f), bucketed(samples, buckets = 1, bucketSec = 100) { it.cpuFraction })
    }

    @Test
    fun `the value is whatever the caller picks`() {
        val samples = listOf(sample(BASE, cpu = 0.1f, mem = 0.5f))

        assertEquals(listOf(0.5f), bucketed(samples) { it.memFraction })
    }

    // ── sumBuckets ────────────────────────────────────────────────────────────

    @Test
    fun `one tab's bars are its used over its capacity`() {
        val bars = sums(listOf(tabSample(0, 0, 500, 4_000), tabSample(1, 0, 1_000, 4_000)))

        assertEquals(listOf(0.125f, 0.25f), bars)
    }

    @Test
    fun `tabs add up as used over capacity, not as a mean of fractions`() {
        // A at 50 % of 1 000 and B at 10 % of 9 000: the fleet is at 14 %, not 30 %.
        val bars = sums(listOf(tabSample(0, 0, 500, 1_000)), listOf(tabSample(0, 0, 900, 9_000)))

        assertEquals(listOf(0.14f), bars)
    }

    @Test
    fun `a tab's samples in one bucket are averaged before the tabs are added`() {
        val bars = sums(
            listOf(tabSample(0, 0, 100, 1_000), tabSample(0, 30, 300, 1_000), tabSample(0, 60, 500, 1_000)),
            listOf(tabSample(0, 0, 100, 1_000)),
        )

        // (300 + 100) over (1 000 + 1 000)
        assertEquals(listOf(0.2f), bars)
    }

    @Test
    fun `a tab with no samples is left out instead of blanking the series`() {
        val bars = sums(listOf(tabSample(0, 0, 500, 1_000)), emptyList())

        assertEquals(listOf(0.5f), bars)
    }

    @Test
    fun `no tabs, or no tab with samples, no bars`() {
        assertEquals(emptyList(), sums())
        assertEquals(emptyList(), sums(emptyList(), emptyList()))
    }

    @Test
    fun `only buckets every tab holds are summed, however the tabs are phased`() {
        val a = listOf(tabSample(0, 0, 100, 1_000), tabSample(1, 0, 200, 1_000), tabSample(2, 0, 300, 1_000))
        val b = listOf(tabSample(1, 90, 400, 1_000), tabSample(2, 90, 500, 1_000), tabSample(3, 90, 600, 1_000))

        val bars = sums(a, b)

        // Buckets 1 and 2: (200 + 400) / 2 000 and (300 + 500) / 2 000.
        assertEquals(listOf(0.3f, 0.4f), bars)
    }

    @Test
    fun `a tab that stopped sampling drops out instead of holding the series at its last bucket`() {
        // A disconnected tab keeps its samples: here it last sampled four buckets before the live one.
        val stopped = listOf(tabSample(0, 0, 900, 1_000), tabSample(1, 0, 900, 1_000))
        val live = listOf(tabSample(0, 0, 100, 1_000), tabSample(1, 0, 100, 1_000), tabSample(5, 0, 200, 1_000))

        assertEquals(listOf(0.1f, 0.1f, 0.2f), sums(stopped, live))
    }

    @Test
    fun `a tab whose last sample is in the bucket before the freshest one still counts`() {
        val lagging = listOf(tabSample(0, 0, 100, 1_000), tabSample(1, 170, 300, 1_000))
        val fresh = listOf(tabSample(0, 0, 100, 1_000), tabSample(1, 0, 300, 1_000), tabSample(2, 0, 500, 1_000))

        // Bucket 2 waits for the lagging tab; bucket 0 and 1 are both tabs' sums.
        assertEquals(listOf(0.1f, 0.3f), sums(lagging, fresh))
    }

    @Test
    fun `tabs that share no bucket give no bars`() {
        val a = listOf(tabSample(0, 0, 100, 1_000), tabSample(2, 0, 100, 1_000))
        val b = listOf(tabSample(1, 0, 100, 1_000))

        assertEquals(emptyList(), sums(a, b))
    }

    @Test
    fun `a tab with no capacity adds nothing to either side`() {
        val bars = sums(listOf(tabSample(0, 0, 0, 0)), listOf(tabSample(0, 0, 250, 1_000)))

        assertEquals(listOf(0.25f), bars)
    }

    @Test
    fun `no capacity anywhere is a zero bar, not a missing one`() {
        assertEquals(listOf(0f), sums(listOf(tabSample(0, 0, 0, 0)), listOf(tabSample(0, 0, 300, 0))))
    }

    @Test
    fun `a bucket boundary separates the bars of the fleet too`() {
        val a = listOf(tabSample(0, 150, 100, 1_000), tabSample(1, 0, 300, 1_000))
        val b = listOf(tabSample(0, 150, 100, 1_000), tabSample(1, 0, 300, 1_000))

        assertEquals(listOf(0.1f, 0.3f), sums(a, b))
    }

    @Test
    fun `the fleet shows the 20 newest shared buckets`() {
        val a = (0 until 25).map { tabSample(it, 0, it * 10L, 1_000) }
        val b = (0 until 25).map { tabSample(it, 0, it * 10L, 1_000) }

        val bars = sums(a, b)

        assertEquals(20, bars.size)
        assertEquals(0.05f, bars.first())
        assertEquals(0.24f, bars.last())
        assertTrue(bars.zipWithNext().all { (x, y) -> x < y })
    }

    @Test
    fun `the memory figures sum the same way`() {
        val tab = listOf(sample(BASE, memUsed = 2_000, memCapacity = 8_000))

        assertEquals(listOf(0.25f), sumBuckets(listOf(tab, tab), used = { it.memUsedBytes }, capacity = { it.memCapacityBytes }))
    }
}
