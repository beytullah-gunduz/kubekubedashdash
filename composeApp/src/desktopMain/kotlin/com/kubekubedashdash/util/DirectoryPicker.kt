package com.kubekubedashdash.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/**
 * Native directory picker. macOS gets the AWT [FileDialog] flipped into
 * directory mode (the Swing [JFileChooser] directory picker looks
 * out-of-place there); every other platform gets [JFileChooser] directly.
 */
object DirectoryPicker {
    private const val MAC_DIRECTORY_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"

    /** Must be called on the EDT (Compose desktop event callbacks already are). */
    fun pick(initialDir: String?): String? = runCatching {
        if (isMac()) pickMac(initialDir) else pickSwing(initialDir)
    }.getOrNull()

    private fun isMac(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    private fun pickMac(initialDir: String?): String? {
        val previous = System.getProperty(MAC_DIRECTORY_DIALOG_PROPERTY)
        try {
            System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "true")
            val dialog = FileDialog(null as Frame?, "Choose Destination", FileDialog.LOAD)
            initialDir?.let { dialog.directory = it }
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            return if (dir != null && file != null) File(dir, file).absolutePath else null
        } finally {
            if (previous != null) {
                System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, previous)
            } else {
                System.clearProperty(MAC_DIRECTORY_DIALOG_PROPERTY)
            }
        }
    }

    private fun pickSwing(initialDir: String?): String? {
        val chooser = JFileChooser(initialDir)
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        val result = chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }
}
