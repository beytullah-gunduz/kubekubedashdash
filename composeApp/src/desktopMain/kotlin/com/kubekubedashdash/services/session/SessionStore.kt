package com.kubekubedashdash.services.session

import com.kubekubedashdash.util.SystemDirectories
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Reads and writes the session file. Writes go to a sibling temp file and
 * are renamed into place so a crash mid-write can never leave a truncated
 * file. Any read problem (missing, malformed, other schema version) yields
 * null: a launch must never fail because of this file.
 */
class SessionStore(private val file: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun load(): SessionSnapshot? {
        if (!file.exists()) return null
        return try {
            val snapshot = json.decodeFromString<SessionSnapshot>(file.readText())
            if (snapshot.version == SessionSnapshot.SCHEMA_VERSION) snapshot else null
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: SessionSnapshot) {
        file.parent?.createDirectories()
        val tmp = file.resolveSibling(file.name + ".tmp")
        try {
            tmp.writeText(json.encodeToString(snapshot))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: UnsupportedOperationException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    companion object {
        const val FILE_NAME = "session.json"

        /** The real file in the application-support directory. Never call from a test. */
        fun default(): SessionStore = SessionStore(Path(SystemDirectories.dataDirectory, FILE_NAME))
    }
}
