package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.CliInvocationFailure
import com.kubekubedashdash.util.ShellEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The token cache: how long a token or a failure is kept, and that parallel callers start one gcloud. */
class GcloudAccessTokenTest {

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

    private val clock = AtomicLong(1_000_000)
    private val fetches = AtomicInteger()

    /** Hands out fake-token-1, fake-token-2, … and counts the calls. */
    private fun tokens(fetch: suspend (Int) -> Result<String> = { n -> Result.success("fake-token-$n") }) = GcloudAccessToken(
        fetch = { fetch(fetches.incrementAndGet()) },
        nowMs = { clock.get() },
    )

    @Test
    fun `a second get inside the lifetime fetches nothing`() = runBlocking<Unit> {
        val token = tokens()

        assertEquals("fake-token-1", token.get().getOrThrow())
        clock.addAndGet(GcloudAccessToken.TTL_MS - 1)
        assertEquals("fake-token-1", token.get().getOrThrow())
        assertEquals(1, fetches.get())
    }

    @Test
    fun `a get once the lifetime is over fetches again`() = runBlocking<Unit> {
        val token = tokens()

        assertEquals("fake-token-1", token.get().getOrThrow())
        clock.addAndGet(GcloudAccessToken.TTL_MS + 1)
        assertEquals("fake-token-2", token.get().getOrThrow())
        assertEquals(2, fetches.get())
    }

    @Test
    fun `the token is trimmed`() = runBlocking<Unit> {
        val token = tokens { Result.success("  fake-token-1\n") }

        assertEquals("fake-token-1", token.get().getOrThrow())
    }

    @Test
    fun `refreshing the token that was refused fetches again`() = runBlocking<Unit> {
        val token = tokens()
        assertEquals("fake-token-1", token.get().getOrThrow())

        assertEquals("fake-token-2", token.refresh(stale = "fake-token-1").getOrThrow())
        assertEquals(2, fetches.get())
        assertEquals("fake-token-2", token.get().getOrThrow())
        assertEquals(2, fetches.get())
    }

    @Test
    fun `refreshing a token that is no longer the cached one fetches nothing`() = runBlocking<Unit> {
        val token = tokens()
        assertEquals("fake-token-1", token.get().getOrThrow())

        assertEquals("fake-token-1", token.refresh(stale = "fake-token-0").getOrThrow())
        assertEquals(1, fetches.get())
    }

    @Test
    fun `five parallel refreshes of the same refused token start one gcloud`() = runBlocking<Unit> {
        val token = tokens { n ->
            delay(50)
            Result.success("fake-token-$n")
        }
        assertEquals("fake-token-1", token.get().getOrThrow())

        val results = coroutineScope { (1..5).map { async(Dispatchers.Default) { token.refresh(stale = "fake-token-1") } }.awaitAll() }

        assertEquals(List(5) { "fake-token-2" }, results.map { it.getOrThrow() })
        assertEquals(2, fetches.get())
    }

    @Test
    fun `five parallel gets with no token start one gcloud`() = runBlocking<Unit> {
        val token = tokens { n ->
            delay(50)
            Result.success("fake-token-$n")
        }

        val results = coroutineScope { (1..5).map { async(Dispatchers.Default) { token.get() } }.awaitAll() }

        assertEquals(List(5) { "fake-token-1" }, results.map { it.getOrThrow() })
        assertEquals(1, fetches.get())
    }

    @Test
    fun `a failure is remembered for ten seconds and then retried`() = runBlocking<Unit> {
        val failure = CliInvocationFailure(1, "", "ERROR: not signed in")
        val token = tokens { n -> if (n == 1) Result.failure(failure) else Result.success("fake-token-$n") }

        assertEquals(failure, token.get().exceptionOrNull())
        clock.addAndGet(5_000)
        assertEquals(failure, token.get().exceptionOrNull())
        assertEquals(1, fetches.get(), "a second query five seconds later must not start another gcloud")

        clock.addAndGet(6_000)
        assertEquals("fake-token-2", token.get().getOrThrow())
        assertEquals(2, fetches.get())
    }

    @Test
    fun `a failed refresh is remembered too`() = runBlocking<Unit> {
        val failure = CliInvocationFailure(1, "", "ERROR: not signed in")
        val token = tokens { n -> if (n == 1) Result.success("fake-token-1") else Result.failure(failure) }
        assertEquals("fake-token-1", token.get().getOrThrow())

        assertEquals(failure, token.refresh(stale = "fake-token-1").exceptionOrNull())
        assertEquals(failure, token.get().exceptionOrNull())
        assertEquals(2, fetches.get())
    }

    @Test
    fun `a blank token is a failure`() = runBlocking<Unit> {
        val token = tokens { Result.success("  \n") }

        val error = assertNotNull(token.get().exceptionOrNull())
        assertIs<CliInvocationFailure>(error)
        assertEquals("gcloud printed no access token", error.message)
        token.get()
        assertEquals(1, fetches.get(), "the blank answer is remembered like any other failure")
    }

    @Test
    fun `a missing gcloud is told apart from a signed-out one`() {
        assertEquals(
            HistoryFailure.GcloudUnavailable,
            GcloudAccessToken.failureOf(CliInvocationFailure(-1, "", "gcloud CLI not found on PATH")),
        )
        assertEquals(
            HistoryFailure.Unavailable,
            GcloudAccessToken.failureOf(CliInvocationFailure(-1, "", "Timed out after 20s: gcloud auth print-access-token --quiet")),
        )
        assertEquals(
            HistoryFailure.NotSignedIn,
            GcloudAccessToken.failureOf(CliInvocationFailure(1, "", "ERROR: You do not currently have an active account selected.")),
        )
        assertEquals(HistoryFailure.NotSignedIn, GcloudAccessToken.failureOf(RuntimeException("boom")))
    }

    @Test
    fun `the default provider cannot start a real gcloud while cloud CLIs are disabled`() = runBlocking<Unit> {
        val result = GcloudAccessToken.shared.get()

        val error = assertNotNull(result.exceptionOrNull(), "the shared token provider found a gcloud despite the disable switch")
        assertEquals(HistoryFailure.GcloudUnavailable, GcloudAccessToken.failureOf(error))
        assertTrue(error.message.orEmpty().endsWith("not found on PATH"), "unexpected failure: ${error.message}")
    }
}
