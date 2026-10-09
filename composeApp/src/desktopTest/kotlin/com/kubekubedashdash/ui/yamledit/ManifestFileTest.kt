package com.kubekubedashdash.ui.yamledit

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reading a file for the Apply window: size limit, UTF-8, line ends, and messages that never name a path. */
class ManifestFileTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kkdd-manifest-file").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(bytes: ByteArray): File = File(dir, "manifest.yaml").also { it.writeBytes(bytes) }

    @Test
    fun `a UTF-8 file is read as is`() {
        val text = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo-cm\ndata:\n  greeting: héllo ✓\n"

        assertEquals(ManifestFile.Loaded(text), readManifestFile(file(text.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun `CRLF line ends become LF and a byte-order mark is dropped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a: 1\r\nb: 2\r\n".toByteArray()

        assertEquals(ManifestFile.Loaded("a: 1\nb: 2\n"), readManifestFile(file(bytes)))
    }

    @Test
    fun `a file larger than the limit is refused with the 5 MB message`() {
        val big = ByteArray(MAX_MANIFEST_FILE_BYTES.toInt() + 1) { 'a'.code.toByte() }

        assertEquals(ManifestFile.Rejected("File is larger than 5 MB."), readManifestFile(file(big)))
    }

    @Test
    fun `a file of exactly the limit is read`() {
        val exact = ByteArray(MAX_MANIFEST_FILE_BYTES.toInt()) { 'a'.code.toByte() }

        val result = readManifestFile(file(exact))

        assertTrue(result is ManifestFile.Loaded && result.text.length == MAX_MANIFEST_FILE_BYTES.toInt())
    }

    @Test
    fun `bytes that are not UTF-8 text are refused`() {
        assertEquals(ManifestFile.Rejected("File is not UTF-8 text."), readManifestFile(file(byteArrayOf(0x61, 0xFF.toByte(), 0xFE.toByte(), 0x62))))
    }

    @Test
    fun `a missing file is refused without naming it`() {
        val missing = File(dir, "gone-secret-name.yaml")

        val result = readManifestFile(missing)

        assertEquals(ManifestFile.Rejected("Couldn't read the file."), result)
        assertFalse(result.toString().contains("gone-secret-name"), result.toString())
        assertFalse(result.toString().contains(dir.path), result.toString())
    }

    @Test
    fun `the dialog offers yaml, yml and json files in any case`() {
        for (name in listOf("a.yaml", "a.yml", "a.json", "A.YAML", "deploy.Yml", "x.y.json")) {
            assertTrue(isManifestFileName(name), name)
        }
        for (name in listOf("a.txt", "yaml", "a.yaml.bak", "a.jsonl", "")) {
            assertFalse(isManifestFileName(name), name)
        }
    }
}
