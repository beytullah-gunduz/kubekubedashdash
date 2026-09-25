package com.kubekubedashdash.util

import androidx.datastore.preferences.core.Preferences
import com.kubekubedashdash.data.datastore.dataStorePreferencesInstance
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.fail

private const val POLL_INTERVAL_MS = 20L

/**
 * Waits until the app's preferences file holds a value for [key] that
 * [matches] accepts, for tests that persist through a fire-and-forget setter.
 *
 * Polls fresh reads instead of collecting `data` once. DataStore 1.2.1 bumps
 * its version before it writes the file, and a `data` collector that starts
 * inside that window reads the old file under the new version, then drops the
 * write's own emission as already seen (`dropWhile { version <= start }` in
 * `DataStoreImpl.data`). `data.first { matches }` then waits for a later write
 * that the test never makes. The window is the file write plus its fsync, so
 * it shows up on the slower Linux runners: v1.21.0, main after 4a9d199, and
 * v1.22.0 each failed there with a 10 s timeout. A read that starts after the
 * write finishes sees the committed value, so the poll catches it on the next
 * pass.
 */
internal suspend fun <T> awaitStoredPreference(
    key: Preferences.Key<T>,
    timeoutMs: Long = 10_000,
    matches: (T?) -> Boolean,
) {
    var last: T? = null
    val found = withTimeoutOrNull(timeoutMs) {
        while (true) {
            last = dataStorePreferencesInstance.data.first()[key]
            if (matches(last)) break
            delay(POLL_INTERVAL_MS)
        }
    }
    if (found == null) fail("preference '${key.name}' still reads $last after $timeoutMs ms")
}
