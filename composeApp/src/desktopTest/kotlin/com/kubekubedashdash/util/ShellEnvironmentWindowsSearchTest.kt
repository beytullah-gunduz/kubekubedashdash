package com.kubekubedashdash.util

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Windows command search, exercised on any host: the PATH parsing takes Windows-style strings,
 * and the file search runs against a temporary copy of a real Windows 11 layout, where `where.exe`
 * reported the AWS CLI in `Program Files\Amazon\AWSCLIV2` and the Google Cloud SDK's `bin` holding
 * an extensionless `gcloud` shell script beside `gcloud.cmd`. Before the fix every Windows PATH
 * entry was dropped (none starts with "/") and only bare names were tried, so neither CLI was found.
 */
class ShellEnvironmentWindowsSearchTest {

    private lateinit var root: File
    private lateinit var awsDir: File
    private lateinit var sdkBin: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("kkdd-windows-search").toFile()
        awsDir = File(root, "Program Files/Amazon/AWSCLIV2").apply { mkdirs() }
        sdkBin = File(root, "AppData/Local/Google/Cloud SDK/google-cloud-sdk/bin").apply { mkdirs() }
        File(awsDir, "aws.exe").createNewFile()
        File(sdkBin, "gcloud").apply { createNewFile() }.setExecutable(true)
        File(sdkBin, "gcloud.cmd").createNewFile()
        File(sdkBin, "gke-gcloud-auth-plugin.exe").createNewFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun posix(): Boolean = !System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    private fun findOnWindows(command: String, dirs: List<File>, pathExt: String? = DEFAULT_PATHEXT): String? = ShellEnvironment.findExecutable(command, dirs.map { it.absolutePath }, windows = true, pathExt = pathExt)

    @Test
    fun `a Windows PATH keeps its absolute directories`() {
        val raw = listOf(
            """C:\WINDOWS\system32""",
            """C:\Program Files\Amazon\AWSCLIV2\""",
            """C:\Users\someone\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin""",
            "",
            "\"C:\\Program Files\\Quoted Tool\"",
            "D:/forward/slashes",
            """\\fileserver\tools""",
            ".",
            "bin",
            """C:relative""",
            """\rooted""",
        ).joinToString(";")

        assertEquals(
            listOf(
                """C:\WINDOWS\system32""",
                """C:\Program Files\Amazon\AWSCLIV2\""",
                """C:\Users\someone\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin""",
                """C:\Program Files\Quoted Tool""",
                "D:/forward/slashes",
                """\\fileserver\tools""",
            ),
            ShellEnvironment.pathEntries(raw, windows = true),
        )
    }

    @Test
    fun `a POSIX PATH still drops relative entries`() {
        assertEquals(
            listOf("/usr/bin", "/opt/homebrew/bin"),
            ShellEnvironment.pathEntries("/usr/bin::.:bin:/opt/homebrew/bin", windows = false),
        )
    }

    @Test
    fun `the Windows search adds the default install directories once and nothing from Unix`() {
        val env = mapOf(
            "PATH" to """C:\WINDOWS\system32;C:\Program Files\Amazon\AWSCLIV2""",
            "ProgramFiles" to """C:\Program Files""",
            "LOCALAPPDATA" to """C:\Users\someone\AppData\Local""",
        )

        assertEquals(
            listOf(
                """C:\WINDOWS\system32""",
                """C:\Program Files\Amazon\AWSCLIV2""",
                """C:\Users\someone\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin""",
            ),
            ShellEnvironment.windowsSearchDirs(env::get),
        )
    }

    @Test
    fun `the CLIs of the reported layout are found under their Windows names`() {
        val dirs = listOf(awsDir, sdkBin)

        assertEquals(File(awsDir, "aws.exe").absolutePath, findOnWindows("aws", dirs))
        assertEquals(File(sdkBin, "gcloud.cmd").absolutePath, findOnWindows("gcloud", dirs), "never the extensionless shell script")
        assertEquals(File(sdkBin, "gke-gcloud-auth-plugin.exe").absolutePath, findOnWindows("gke-gcloud-auth-plugin", dirs))
        assertEquals(File(sdkBin, "gke-gcloud-auth-plugin.exe").absolutePath, findOnWindows("gke-gcloud-auth-plugin.exe", dirs))
        assertNull(findOnWindows("kubelogin", dirs))
    }

    /**
     * Windows only — the release workflow runs this suite on windows-latest before it builds the
     * MSI. CliRunner starts the resolved absolute path, and Windows starts a .cmd file through
     * cmd.exe, whose quoting is the classic trap for a path with a space (`Cloud SDK`).
     */
    @Test
    fun `on Windows the resolved cmd file starts from a path with a space, arguments intact`() {
        if (posix()) return
        File(sdkBin, "gcloud.cmd").writeText("@echo off\r\necho %*\r\n")
        val path = assertNotNull(findOnWindows("gcloud", listOf(sdkBin)))
        assertEquals(File(sdkBin, "gcloud.cmd").absolutePath, path)

        val process = ProcessBuilder(path, "container", "clusters", "list", "--project=my-proj", "--format=json", "--quiet")
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the .cmd file did not exit")
        assertEquals("container clusters list --project=my-proj --format=json --quiet", output)
    }

    @Test
    fun `an earlier PATH directory wins, then PATHEXT order within a directory`() {
        val first = File(root, "first").apply { mkdirs() }
        val second = File(root, "second").apply { mkdirs() }
        File(first, "tool.cmd").createNewFile()
        File(second, "tool.exe").createNewFile()
        assertEquals(File(first, "tool.cmd").absolutePath, findOnWindows("tool", listOf(first, second)))

        File(first, "tool.exe").createNewFile()
        assertEquals(File(first, "tool.exe").absolutePath, findOnWindows("tool", listOf(first, second)))
        assertEquals(File(first, "tool.cmd").absolutePath, findOnWindows("tool", listOf(first, second), pathExt = ".CMD;.EXE"))
    }

    @Test
    fun `script types are never picked and a PATHEXT without launchable types falls back to the defaults`() {
        val dir = File(root, "scripts").apply { mkdirs() }
        File(dir, "tool.vbs").createNewFile()
        assertNull(findOnWindows("tool", listOf(dir), pathExt = ".VBS;.JS"))

        File(dir, "tool.exe").createNewFile()
        assertEquals(File(dir, "tool.exe").absolutePath, findOnWindows("tool", listOf(dir), pathExt = ".VBS;.JS"))
        assertEquals(File(dir, "tool.exe").absolutePath, findOnWindows("tool", listOf(dir), pathExt = null))
    }

    @Test
    fun `the POSIX search tries the bare name and needs the execute bit`() {
        if (!posix()) return
        val dir = File(root, "posix").apply { mkdirs() }
        val tool = File(dir, "tool").apply { createNewFile() }
        File(dir, "tool.exe").createNewFile()

        assertNull(ShellEnvironment.findExecutable("tool", listOf(dir.absolutePath), windows = false, pathExt = DEFAULT_PATHEXT))
        tool.setExecutable(true)
        assertEquals(tool.absolutePath, ShellEnvironment.findExecutable("tool", listOf(dir.absolutePath), windows = false, pathExt = DEFAULT_PATHEXT))
    }

    private companion object {
        // The usual Windows PATHEXT, script types included.
        const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD;.VBS;.VBE;.JS;.JSE;.WSF;.WSH;.MSC"
    }
}
