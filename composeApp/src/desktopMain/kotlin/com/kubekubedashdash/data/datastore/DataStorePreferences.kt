package com.kubekubedashdash.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toPath
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val PREFERENCE_DATASTORE = "settings_preferences.preferences_pb"

/** Between the file name and the timestamp of the copy kept when the file could not be parsed. */
internal const val CORRUPT_BACKUP_INFIX = ".corrupt-"

private val BACKUP_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private val log = LoggerFactory.getLogger("PreferenceStorage")

val dataStorePreferencesInstance: DataStore<Preferences> by lazy {
    preferencesDataStore(Path.of(SystemDirectories.dataDirectory, PREFERENCE_DATASTORE))
}

/**
 * The app's preferences store over [path]. A file that does not parse is
 * copied aside and replaced with defaults (see [replaceCorruptPreferences])
 * instead of failing every read and write for the rest of the process, which
 * is what DataStore's default handler does. Tests build one over a scratch
 * path; DataStore allows one instance per file per process.
 */
internal fun preferencesDataStore(
    path: Path,
    health: PreferenceStorageHealth = PreferenceStorageHealth.Default,
): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
    corruptionHandler = ReplaceFileCorruptionHandler { cause -> replaceCorruptPreferences(path, cause, health) },
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile = { path.toString().toPath() },
)

/**
 * DataStore's corruption hook: keep a copy of the unparseable file next to it
 * (`<name>.corrupt-<timestamp>`), report, and hand DataStore empty preferences
 * to write in its place. If the copy cannot be made the original exception is
 * rethrown with the copy failure suppressed — the file is left as evidence and
 * the read fails as before, which the repositories then report.
 */
internal fun replaceCorruptPreferences(
    path: Path,
    cause: CorruptionException,
    health: PreferenceStorageHealth,
): Preferences {
    // If the replacement write failed earlier this run, DataStore re-enters
    // here on every read and edit; the one copy already kept is enough.
    if (health.state.value.load?.backupFileName != null) return emptyPreferences()
    val backup = path.resolveSibling(path.fileName.toString() + CORRUPT_BACKUP_INFIX + LocalDateTime.now().format(BACKUP_TIMESTAMP))
    try {
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        log.warn(
            "Preferences file {} could not be parsed and no copy could be kept ({}); leaving it in place",
            path.fileName,
            e::class.simpleName,
        )
        cause.addSuppressed(e)
        throw cause
    }
    log.warn("Preferences file {} could not be parsed; a copy was kept as {} and defaults are in use", path.fileName, backup.fileName)
    health.reportLoadFault(
        LoadFault(exceptionClass = cause::class.simpleName ?: "CorruptionException", backupFileName = backup.fileName.toString()),
    )
    return emptyPreferences()
}
