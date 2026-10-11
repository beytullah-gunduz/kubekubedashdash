package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.CliInvocationFailure
import com.kubekubedashdash.util.CliRunner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The bearer token for Managed Prometheus, from gcloud's signed-in account. Never logged, stored or shown. */
class GcloudAccessToken internal constructor(
    private val fetch: suspend () -> Result<String>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cached: String? = null
    private var fetchedAtMs = 0L
    private var lastFailure: Throwable? = null
    private var failedAtMs = 0L

    /**
     * The cached token, or a fresh one when none is cached or it is older than [TTL_MS]. A failure
     * is remembered for [FAILURE_TTL_MS] only, so one refresh's parallel queries start one gcloud,
     * not five, and the next 30 s refresh retries.
     */
    suspend fun get(): Result<String> = mutex.withLock { current() ?: fetchLocked() }

    /** After a 401, with the token that was refused. Re-fetches only while that token is still the cached one, so parallel 401s start one gcloud. */
    suspend fun refresh(stale: String): Result<String> = mutex.withLock {
        if (cached != stale) current()?.let { return@withLock it }
        cached = null
        fetchLocked()
    }

    private fun current(): Result<String>? {
        val token = cached ?: return null
        return if (nowMs() - fetchedAtMs < TTL_MS) Result.success(token) else null
    }

    private suspend fun fetchLocked(): Result<String> {
        lastFailure?.let { if (nowMs() - failedAtMs < FAILURE_TTL_MS) return Result.failure(it) }
        val fetched = fetch().map { it.trim() }
        val token = fetched.getOrNull()
        if (token.isNullOrEmpty()) {
            cached = null
            val failure = fetched.exceptionOrNull() ?: CliInvocationFailure(0, "", "gcloud printed no access token")
            lastFailure = failure
            failedAtMs = nowMs()
            return Result.failure(failure)
        }
        lastFailure = null
        cached = token
        fetchedAtMs = nowMs()
        return Result.success(token)
    }

    companion object {
        // gcloud's access tokens live an hour; refresh ten minutes early.
        const val TTL_MS = 50 * 60 * 1000L

        // Shorter than the 30 s chart refresh, so a sign-in is picked up on the next one.
        const val FAILURE_TTL_MS = 10_000L

        val shared: GcloudAccessToken by lazy {
            GcloudAccessToken(fetch = {
                CliRunner.run(listOf("gcloud", "auth", "print-access-token", "--quiet"), timeoutSeconds = 20, mergeStderr = false)
            })
        }

        /** "not found on PATH" is CliRunner's missing-binary failure (also what disabled cloud CLIs produce). */
        fun failureOf(error: Throwable): HistoryFailure {
            val message = error.message.orEmpty()
            return when {
                error is CliInvocationFailure && message.endsWith("not found on PATH") -> HistoryFailure.GcloudUnavailable
                error is CliInvocationFailure && message.startsWith("Timed out") -> HistoryFailure.Unavailable
                else -> HistoryFailure.NotSignedIn
            }
        }
    }
}
