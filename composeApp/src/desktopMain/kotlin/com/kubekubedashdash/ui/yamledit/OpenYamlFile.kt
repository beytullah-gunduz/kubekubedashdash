package com.kubekubedashdash.ui.yamledit

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The largest manifest file the Apply window opens: an editor buffer is for text people read, not for archives. */
internal const val MAX_MANIFEST_FILE_BYTES = 5L * 1024 * 1024

/** What reading a manifest file gave: its text (line ends normalised), or the reason it was not read. Never the path, never the content. */
internal sealed interface ManifestFile {
    data class Loaded(val text: String) : ManifestFile

    data class Rejected(val message: String) : ManifestFile
}

/** Whether the "Open file…" dialog offers [name]: `.yaml`, `.yml` or `.json`, whatever the case. */
internal fun isManifestFileName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json")
}

/**
 * Reads [file] as UTF-8 text for the editor: refuses more than [maxBytes] ("File is larger than 5 MB."),
 * strict decoding (a binary file is not text), a leading byte-order mark dropped and `\r\n` turned
 * into `\n`, which is what the editor and the parser count lines by. Blocking: call it off the EDT.
 *
 * The messages name no path and quote no content: an I/O failure's own message carries the absolute
 * path (and so the account name), so it is never passed on.
 */
internal fun readManifestFile(file: File, maxBytes: Long = MAX_MANIFEST_FILE_BYTES): ManifestFile {
    val bytes = try {
        // One byte past the limit is enough to tell: a device file or a growing file cannot run us out of memory.
        file.inputStream().use { it.readNBytes((maxBytes + 1).toInt()) }
    } catch (_: IOException) {
        return ManifestFile.Rejected("Couldn't read the file.")
    } catch (_: SecurityException) {
        return ManifestFile.Rejected("Couldn't read the file.")
    }
    if (bytes.size > maxBytes) return ManifestFile.Rejected("File is larger than 5 MB.")
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        return ManifestFile.Rejected("File is not UTF-8 text.")
    }
    return ManifestFile.Loaded(text.removePrefix("\uFEFF").replace("\r\n", "\n"))
}

/**
 * The native "Open YAML" dialog of the Apply window. Like [com.kubekubedashdash.util.DirectoryPicker]
 * it runs synchronously on the caller's thread: a Compose `onClick`, which is the EDT, where a
 * modal AWT dialog pumps events itself. Only the chosen file is handed back; reading it
 * ([readManifestFile]) is the caller's job, off the EDT.
 */
internal object YamlFilePicker {
    /** The file the person chose, or null when they cancelled (or the platform has no file dialog). */
    fun pick(): File? = runCatching {
        val dialog = FileDialog(null as Frame?, "Open YAML", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> isManifestFileName(name) }
        dialog.isVisible = true
        val directory = dialog.directory
        val name = dialog.file
        if (directory != null && name != null) File(directory, name) else null
    }.getOrNull()
}
