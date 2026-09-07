package com.kubekubedashdash.data.repository

import androidx.datastore.core.CorruptionException
import com.kubekubedashdash.data.datastore.LoadFault
import com.kubekubedashdash.data.datastore.PreferenceStorageHealth
import com.kubekubedashdash.data.datastore.SaveFault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import org.slf4j.LoggerFactory
import java.io.IOException

private val log = LoggerFactory.getLogger("PreferenceStorage")

/**
 * Back-off before each re-read; the list's size is the retry budget. The
 * delays total 1.75 s, well under the 5 s that AppViewModel's launch-time
 * reader waits for `preferencesLoaded`, so a read that recovers, or is given
 * up on, still lands inside that wait; the failed reads themselves add to that.
 */
internal val PREFERENCE_READ_RETRY_DELAYS_MS: List<Long> = listOf(250L, 500L, 1_000L)

/**
 * Recovery for a repository's `dataStore.data` collector. DataStore re-runs
 * its initial read on every new collection until one succeeds, so a read
 * that failed on the way in (a busy or briefly unreadable file) is retried
 * with back-off. A [CorruptionException] is never retried: the store's
 * handler has already replaced a file it could parse away, so one reaching
 * here means that replacement itself failed. When the budget is spent the
 * failure is logged with its class, reported to [health], and the flow
 * completes normally, so a `preferencesLoaded` guard falls open on the
 * defaults instead of leaving launch-time readers waiting. Cancellation of
 * the collector passes through untouched (kotlinx `catch`/`retryWhen` never
 * intercept the collector's own cancellation).
 */
internal fun <T> Flow<T>.recoveringPreferenceReads(
    owner: String,
    health: PreferenceStorageHealth = PreferenceStorageHealth.Default,
    delaysMs: List<Long> = PREFERENCE_READ_RETRY_DELAYS_MS,
): Flow<T> = retryWhen { cause, attempt ->
    val retry = cause !is CorruptionException && attempt < delaysMs.size
    if (retry) {
        val wait = delaysMs[attempt.toInt()]
        log.warn("{}: preferences read failed ({}); retrying in {} ms", owner, cause::class.simpleName, wait)
        delay(wait)
    }
    retry
}.catch { cause ->
    warn(cause, "{}: preferences could not be read ({}); defaults are in use", owner, cause::class.simpleName)
    health.reportLoadFault(LoadFault(exceptionClass = cause::class.simpleName ?: "Exception"))
}

/**
 * For a repository's write scope: an `edit` that throws is logged and
 * reported instead of reaching the JVM's uncaught-exception handler as an
 * ERROR with a stack trace per keystroke.
 */
internal fun preferenceWriteFailureHandler(
    owner: String,
    health: PreferenceStorageHealth = PreferenceStorageHealth.Default,
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, cause -> reportWriteFailure(owner, health, cause) }

/**
 * Runs one queued write for a serial consumer: a failure is reported and the
 * consumer goes on to the next write; cancellation propagates.
 */
internal suspend fun runPreferenceWrite(
    owner: String,
    health: PreferenceStorageHealth = PreferenceStorageHealth.Default,
    write: suspend () -> Unit,
) {
    try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportWriteFailure(owner, health, e)
    }
}

private fun reportWriteFailure(owner: String, health: PreferenceStorageHealth, cause: Throwable) {
    warn(cause, "{}: saving preferences failed ({}); the change may be lost", owner, cause::class.simpleName)
    health.reportSaveFault(SaveFault(exceptionClass = cause::class.simpleName ?: "Exception"))
}

/**
 * A storage failure (an IOException, which a CorruptionException is) is
 * logged by class only: its message usually repeats the file's path.
 * Anything else is a bug and keeps the stack trace the JVM uncaught
 * handler used to print for it.
 */
private fun warn(cause: Throwable, message: String, vararg args: Any?) {
    if (cause is IOException) log.warn(message, *args) else log.warn(message, *args, cause)
}
