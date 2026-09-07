package com.kubekubedashdash.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.NoSuchFileException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A preferences file that does not parse used to fail every read and every
 * write for the rest of the process (DataStore's default corruption handler
 * rethrows). The store built by [preferencesDataStore] copies such a file
 * aside, reports the fault with the copy's name, and continues on defaults
 * so later saves land.
 *
 * Runs only against a scratch directory under the Gradle test-data
 * directory (`kkdd.dataDir`), never the developer's real preferences file.
 */
class CorruptPreferencesFileTest {

    private val health = PreferenceStorageHealth()
    private lateinit var dir: File

    @BeforeTest
    fun scratchDirectoryUnderTestData() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        dir = File(SystemDirectories.dataDirectory, "corrupt-prefs-" + UUID.randomUUID()).also { it.mkdirs() }
    }

    @AfterTest
    fun removeScratchDirectory() {
        dir.deleteRecursively()
    }

    @Test
    fun `an unparseable preferences file is copied aside, replaced with defaults, and reported`() = runBlocking<Unit> {
        val file = File(dir, FILE_NAME)
        file.writeBytes(GARBAGE)
        val store = preferencesDataStore(file.toPath(), health)

        val first = withTimeout(10_000) { store.data.first() }

        assertTrue(first.asMap().isEmpty(), "the replacement is empty preferences")
        val backups = dir.listFiles().orEmpty().filter { it.name.startsWith(FILE_NAME + CORRUPT_BACKUP_INFIX) }
        assertEquals(1, backups.size, "exactly one copy kept next to the file: ${dir.list()?.toList()}")
        assertContentEquals(GARBAGE, backups.single().readBytes(), "the copy is byte-identical to the unparseable file")
        val fault = assertNotNull(health.state.value.load, "the fault is reported")
        assertEquals(backups.single().name, fault.backupFileName)
        assertEquals("CorruptionException", fault.exceptionClass)
        assertFalse(file.readBytes().contentEquals(GARBAGE), "the file itself was rewritten")

        // The store is usable afterwards: a save lands and reads back.
        val key = stringPreferencesKey("probe")
        store.edit { it[key] = "after" }
        assertEquals("after", withTimeout(10_000) { store.data.first()[key] })
    }

    @Test
    fun `once a copy is recorded the handler returns defaults without copying again`() {
        health.reportLoadFault(LoadFault("CorruptionException", "already.corrupt-1"))
        val missing = File(dir, FILE_NAME).toPath()

        val replacement = replaceCorruptPreferences(missing, CorruptionException("again"), health)

        assertTrue(replacement.asMap().isEmpty())
        assertEquals(emptyList(), dir.list().orEmpty().toList(), "no copy attempted: the file was never read")
        assertEquals(LoadFault("CorruptionException", "already.corrupt-1"), health.state.value.load)
    }

    @Test
    fun `when no copy can be made the handler rethrows the corruption and reports nothing`() {
        val missing = File(dir, FILE_NAME).toPath()
        val corruption = CorruptionException("unparseable")

        val thrown = assertFailsWith<CorruptionException> { replaceCorruptPreferences(missing, corruption, health) }

        assertSame(corruption, thrown, "the original exception, so the collectors see a CorruptionException")
        assertTrue(thrown.suppressed.single() is NoSuchFileException, "the copy failure rides along: ${thrown.suppressed.toList()}")
        assertNull(health.state.value.load, "no copy, no fault from the handler; the collectors report the read failure")
        assertEquals(emptyList(), dir.list().orEmpty().toList())
    }

    private companion object {
        const val FILE_NAME = "settings_preferences.preferences_pb"

        /** Field 1, length-delimited, claiming 127 bytes that are not there: a truncated message. */
        val GARBAGE = byteArrayOf(0x0A, 0x7F)
    }
}
