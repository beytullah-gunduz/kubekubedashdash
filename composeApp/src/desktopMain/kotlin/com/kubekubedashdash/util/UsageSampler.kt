package com.kubekubedashdash.util

import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.ResourceUsageSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runInterruptible

/** Seconds between samples; a sample's `epochSec` is aligned down to this. */
internal const val USAGE_SAMPLE_SLOT_SEC = 30L

/** Samples kept: one hour at [USAGE_SAMPLE_SLOT_SEC]. */
internal const val USAGE_SAMPLE_CAPACITY = 120

/** Seconds one bar of the usage sparklines spans. */
internal const val USAGE_BUCKET_SEC = 180L

/** Bars the usage sparklines show: [USAGE_BUCKETS] x [USAGE_BUCKET_SEC] is the last hour. */
internal const val USAGE_BUCKETS = 20

/** One reading of the cluster's CPU and memory, over the namespaces the session was following. */
data class UsageSample(
    val epochSec: Long,
    val cpuFraction: Float,
    val memFraction: Float,
    val cpuUsedMillis: Long,
    val cpuCapacityMillis: Long,
    val memUsedBytes: Long,
    val memCapacityBytes: Long,
)

/**
 * Samples a session's CPU and memory usage for as long as the session lives,
 * so the Overview, Nodes and All Clusters sparklines show the last hour
 * whether or not their screen was open. It holds the last
 * [USAGE_SAMPLE_CAPACITY] readings, one per [USAGE_SAMPLE_SLOT_SEC] slot, and
 * clears them when the namespace selection or the connected context changes;
 * a reconnect to the same context under the same selection keeps them.
 *
 * Takes its inputs as parameters because the connection trigger and the
 * informer factory are private to [ReactiveKubeClient].
 *
 * @param trigger the connection trigger: bumps on every connection transition.
 * @param park suspends forever while disconnected, so a bump that finds no live
 *   client parks the loop instead of failing it.
 * @param fetch one blocking read of the usage; it runs under `runInterruptible`.
 * @param intervalMs time between samples; zero or less never starts the loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageSampler(
    scope: CoroutineScope,
    trigger: Flow<Long>,
    private val park: suspend () -> Unit,
    namespaceScope: StateFlow<NamespaceScope>,
    private val connectedContext: () -> String?,
    private val fetch: (NamespaceScope) -> ResourceUsageSummary,
    private val intervalMs: Long,
    private val nowEpochSec: () -> Long,
) {
    /** What a buffer's samples were read under. */
    private data class SampleKey(val scope: NamespaceScope, val context: String?)

    private data class Buffer(val key: SampleKey?, val samples: List<UsageSample>)

    // The samples carry the key they were read under, so a fetch that finishes
    // after a reset cannot put an old selection's reading into the new series.
    private val lock = Any()
    private var buffer = Buffer(null, emptyList())
    private val _samples = MutableStateFlow<List<UsageSample>>(emptyList())

    /** The kept samples, oldest first. */
    val samples: StateFlow<List<UsageSample>> = _samples.asStateFlow()

    init {
        if (intervalMs > 0) {
            combine(namespaceScope, trigger) { selection, _ -> selection }
                .flatMapLatest { selection -> flow<Unit> { sampleLoop(selection) } }
                .launchIn(scope)
        }
    }

    private fun update(change: (Buffer) -> Buffer) = synchronized(lock) {
        buffer = change(buffer)
        _samples.value = buffer.samples
    }

    private suspend fun sampleLoop(selection: NamespaceScope) {
        park()
        // Read once, after park(): the key every write of this run is checked against.
        val key = SampleKey(selection, connectedContext())
        update { if (it.key != key) Buffer(key, emptyList()) else it }
        while (true) {
            sampleOnce(selection, key)
            delay(intervalMs)
        }
    }

    private suspend fun sampleOnce(selection: NamespaceScope, key: SampleKey) {
        // A failed or skipped read adds nothing: the polls that feed the screens
        // already count the failures against the connection.
        val summary = try {
            runInterruptible(Dispatchers.IO) { fetch(selection) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (summary == null || !summary.metricsAvailable) return
        val now = nowEpochSec()
        val sample = UsageSample(
            epochSec = now - now % USAGE_SAMPLE_SLOT_SEC,
            cpuFraction = fraction(summary.cpuUsedMillis, summary.cpuCapacityMillis),
            memFraction = fraction(summary.memoryUsedBytes, summary.memoryCapacityBytes),
            cpuUsedMillis = summary.cpuUsedMillis,
            cpuCapacityMillis = summary.cpuCapacityMillis,
            memUsedBytes = summary.memoryUsedBytes,
            memCapacityBytes = summary.memoryCapacityBytes,
        )
        update { current ->
            // A later loop owns the buffer: this reading predates the reset.
            if (current.key != key) current else Buffer(key, appendOrReplace(current.samples, sample))
        }
    }

    // Zero capacity (a namespace-limited account cannot list nodes) is a 0 % reading, not a gap.
    private fun fraction(used: Long, capacity: Long): Float = if (capacity > 0) used.toFloat() / capacity else 0f

    private fun appendOrReplace(samples: List<UsageSample>, sample: UsageSample): List<UsageSample> {
        // A reconnect samples at once and can land in the slot of the last reading.
        val kept = if (samples.lastOrNull()?.epochSec == sample.epochSec) samples.dropLast(1) else samples
        return (kept + sample).takeLast(USAGE_SAMPLE_CAPACITY)
    }
}

private fun bucketStart(epochSec: Long, bucketSec: Long): Long = epochSec - epochSec % bucketSec

/**
 * The newest [buckets] bucket slots of [samples] that hold a sample, as the
 * mean of [value] per bucket, oldest first. Buckets are epoch-aligned and the
 * newest is the one holding the last sample, never the wall clock, so a gap
 * stays a gap instead of pulling older buckets in. Empty buckets are skipped.
 */
fun bucketed(
    samples: List<UsageSample>,
    buckets: Int = USAGE_BUCKETS,
    bucketSec: Long = USAGE_BUCKET_SEC,
    value: (UsageSample) -> Float,
): List<Float> {
    val newest = bucketStart(samples.lastOrNull()?.epochSec ?: return emptyList(), bucketSec)
    val oldest = newest - (buckets - 1) * bucketSec
    return samples
        .groupBy { bucketStart(it.epochSec, bucketSec) }
        .filterKeys { it in oldest..newest }
        .toSortedMap()
        .values
        .map { inBucket -> (inBucket.sumOf { value(it).toDouble() } / inBucket.size).toFloat() }
}

/**
 * The fleet's usage per bucket: for each bucket that every tab with a sample
 * holds, the sum of the tabs' mean [used] over the sum of their mean
 * [capacity], oldest first, 0 when the capacities sum to zero. A tab with no
 * sample (no metrics server) is left out rather than blanking the series, and
 * so is a tab whose last sample is older than the bucket before the freshest
 * tab's: a disconnected tab keeps its samples, and would otherwise hold the
 * series at its last reading. Slots are chosen as in [bucketed], from the
 * newest bucket all those tabs share.
 */
fun sumBuckets(
    perTab: List<List<UsageSample>>,
    used: (UsageSample) -> Long,
    capacity: (UsageSample) -> Long,
    buckets: Int = USAGE_BUCKETS,
    bucketSec: Long = USAGE_BUCKET_SEC,
): List<Float> {
    val sampled = perTab.filter { it.isNotEmpty() }
    val freshest = sampled.maxOfOrNull { bucketStart(it.last().epochSec, bucketSec) } ?: return emptyList()
    val perTabMeans = sampled
        .filter { freshest - bucketStart(it.last().epochSec, bucketSec) <= bucketSec }
        .map { samples ->
            samples
                .groupBy { bucketStart(it.epochSec, bucketSec) }
                .mapValues { (_, inBucket) ->
                    val meanUsed = inBucket.sumOf { used(it) }.toDouble() / inBucket.size
                    val meanCapacity = inBucket.sumOf { capacity(it) }.toDouble() / inBucket.size
                    meanUsed to meanCapacity
                }
        }
    if (perTabMeans.isEmpty()) return emptyList()
    val shared = perTabMeans.map { it.keys }.reduce { a, b -> a intersect b }
    val newest = shared.maxOrNull() ?: return emptyList()
    val oldest = newest - (buckets - 1) * bucketSec
    return shared.filter { it in oldest..newest }.sorted().map { start ->
        val usedSum = perTabMeans.sumOf { it.getValue(start).first }
        val capacitySum = perTabMeans.sumOf { it.getValue(start).second }
        if (capacitySum > 0) (usedSum / capacitySum).toFloat() else 0f
    }
}
