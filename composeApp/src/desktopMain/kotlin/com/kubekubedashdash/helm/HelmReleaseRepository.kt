package com.kubekubedashdash.helm

import com.kubekubedashdash.util.isForbidden
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches and decodes Helm payloads on demand and keeps the results.
 *
 * The informers hold metadata only; the payload of one revision (up to about 1 MiB of base64)
 * is fetched when a screen needs it. At most [maxConcurrentFetches] summary loads run at once,
 * plus [maxConcurrentDetails] detail loads on permits of their own, so the open panel never
 * waits behind a list's queue of summaries. Loads for one revision are shared, and results are
 * cached by [HelmRevisionRef.cacheKey] in two LRU maps (summaries for list rows and History,
 * full details for the open panel).
 *
 * Logging stays at the fixed messages below: never a payload, never a decode exception's text.
 */
class HelmReleaseRepository(
    private val scope: CoroutineScope,
    /** Blocking. The Helm payload text, or null when the object no longer exists. Throws on API errors and HelmDecodeException on bad Secret data. */
    private val fetch: (HelmRevisionRef) -> String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    summaryCapacity: Int = 1_024,
    detailCapacity: Int = 4,
    maxConcurrentFetches: Int = 4,
    maxConcurrentDetails: Int = 2,
) {
    private val log = LoggerFactory.getLogger(HelmReleaseRepository::class.java)

    private val lock = Any()
    private val summaries = lruMap<HelmCacheKey, HelmDecoded<HelmReleaseSummary>>(summaryCapacity)
    private val details = lruMap<HelmCacheKey, HelmDecoded<HelmReleaseDetail>>(detailCapacity)
    private val summaryPermits = Semaphore(maxConcurrentFetches)
    private val detailPermits = Semaphore(maxConcurrentDetails)
    private val inFlight = ConcurrentHashMap<Pair<String, HelmCacheKey>, Deferred<HelmDecoded<*>>>()

    suspend fun summary(ref: HelmRevisionRef): HelmDecoded<HelmReleaseSummary> {
        val key = ref.cacheKey
        cachedSummary(key)?.let { return it }
        return guarded { dedupe(SUMMARY to key, summaryPermits) { loadSummary(ref, key) } }
    }

    suspend fun detail(ref: HelmRevisionRef): HelmDecoded<HelmReleaseDetail> {
        val key = ref.cacheKey
        cachedDetail(key)?.let { return it }
        return guarded { dedupe(DETAIL to key, detailPermits) { loadDetail(ref, key) } }
    }

    /** Non-suspending peek, for a row that was decoded before. */
    fun cachedSummary(ref: HelmRevisionRef): HelmDecoded<HelmReleaseSummary>? = cachedSummary(ref.cacheKey)

    private fun cachedSummary(key: HelmCacheKey): HelmDecoded<HelmReleaseSummary>? = synchronized(lock) { summaries[key] }

    private fun cachedDetail(key: HelmCacheKey): HelmDecoded<HelmReleaseDetail>? = synchronized(lock) { details[key] }

    /**
     * Runs [load] once per key however many callers ask. A caller that stops waiting does
     * not cancel the load.
     */
    private suspend fun <T> dedupe(
        key: Pair<String, HelmCacheKey>,
        permits: Semaphore,
        load: suspend () -> HelmDecoded<T>,
    ): HelmDecoded<T> {
        val deferred = inFlight.computeIfAbsent(key) {
            // LAZY: nothing can complete before the completion handler below is attached
            // (attaching it inside computeIfAbsent could run it at once on a finished
            // Deferred and trip the map's "Recursive update" check).
            scope.async(dispatcher, start = CoroutineStart.LAZY) { permits.withPermit { load() } }
        }
        // The two-argument remove: a stale handler never evicts a newer entry. Several
        // callers may each attach one; harmless.
        deferred.invokeOnCompletion { inFlight.remove(key, deferred) }
        deferred.start()
        @Suppress("UNCHECKED_CAST")
        return deferred.await() as HelmDecoded<T>
    }

    /**
     * A load cancelled underneath an active caller (the session scope closed) is a transient
     * failure; the caller's own cancellation is rethrown.
     */
    private suspend fun <T> guarded(block: suspend () -> HelmDecoded<T>): HelmDecoded<T> = try {
        block()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        HelmDecoded.Failed(COULDNT_READ, transient = true)
    }

    private fun loadSummary(ref: HelmRevisionRef, key: HelmCacheKey): HelmDecoded<HelmReleaseSummary> {
        // A caller that missed the cache just before an earlier load for the key finished
        // arrives here with a fresh load; answer it from the cache instead of fetching again.
        cachedSummary(key)?.let { return it }
        val result = fetchAndDecode(ref) { json -> HelmReleaseCodec.parseSummary(json) }
        if (!result.isTransient()) synchronized(lock) { summaries[key] = result }
        return result
    }

    private fun loadDetail(ref: HelmRevisionRef, key: HelmCacheKey): HelmDecoded<HelmReleaseDetail> {
        cachedDetail(key)?.let { return it }
        val result = fetchAndDecode(ref) { json -> HelmReleaseCodec.parseDetail(json, ref.namespace) }
        if (!result.isTransient()) {
            synchronized(lock) {
                details[key] = result
                if (result is HelmDecoded.Ok) summaries[key] = HelmDecoded.Ok(result.value.summary)
            }
        }
        return result
    }

    private fun HelmDecoded<*>.isTransient(): Boolean = this is HelmDecoded.Failed && transient

    /** fetch -> decode -> [parse], with every failure mapped to a fixed message. */
    private fun <T> fetchAndDecode(ref: HelmRevisionRef, parse: (String) -> T): HelmDecoded<T> {
        val startedAt = System.nanoTime()
        val payload = try {
            fetch(ref) ?: return HelmDecoded.Missing
        } catch (e: HelmDecodeException) {
            return HelmDecoded.Failed(e.message ?: NOT_A_RELEASE, transient = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = if (isForbidden(e)) "Reading this release was refused (HTTP 403)." else COULDNT_READ
            return HelmDecoded.Failed(message, transient = true)
        }
        return try {
            val json = HelmReleaseCodec.decodeToJson(payload)
            val value = parse(json)
            log.debug(
                "Decoded Helm release {}/{} v{} ({} bytes JSON) in {} ms",
                ref.namespace,
                ref.releaseName,
                ref.revision,
                json.length,
                (System.nanoTime() - startedAt) / 1_000_000,
            )
            HelmDecoded.Ok(value)
        } catch (e: HelmDecodeException) {
            HelmDecoded.Failed(e.message ?: NOT_A_RELEASE, transient = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            HelmDecoded.Failed("The release payload is too large to decode.", transient = false)
        } catch (_: Throwable) {
            // An Error escaping the async would be rethrown from await() into whoever called it.
            HelmDecoded.Failed(NOT_A_RELEASE, transient = false)
        }
    }

    private companion object {
        const val SUMMARY = "summary"
        const val DETAIL = "detail"
        const val COULDNT_READ = "Couldn't read this release from the cluster."
        const val NOT_A_RELEASE = "The release payload is not a Helm release."

        /** Access-ordered, so the eldest entry is the least recently used. Guard every access with the repository's lock. */
        fun <K, V> lruMap(capacity: Int): LinkedHashMap<K, V> = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
        }
    }
}
