package com.kubekubedashdash.uismoke

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the UI smoke reads a hot run's files: the "Started" line, the pid file and the JVM argfile
 * that proves a run is demo-only. Pure text in, no process and no real build directory.
 */
class HotRunFilesTest {
    private lateinit var dataDir: File
    private lateinit var otherDir: File

    @BeforeTest
    fun createDirs() {
        dataDir = createTempDirectory("kkdd-hotrun-data").toFile()
        otherDir = createTempDirectory("kkdd-hotrun-other").toFile()
    }

    @AfterTest
    fun deleteDirs() {
        dataDir.deleteRecursively()
        otherDir.deleteRecursively()
    }

    private val emptyKubeconfig = File("/tmp/example-repo/composeApp/build/test-kubeconfig/empty.yaml")

    private fun goodProps(): Map<String, String> = mapOf(
        "kubeconfig" to "/tmp/example-repo/composeApp/build/test-kubeconfig/empty.yaml",
        "kkdd.dataDir" to dataDir.path,
        "kkdd.uiTestHooks" to "true",
        "kkdd.disableCloudClis" to "true",
    )

    // ---------------------------------------------------------------- argfileProps

    @Test
    fun `argfileProps reads quoted lines`() {
        val text = """
            "-Dkkdd.dataDir=/tmp/example-repo/build/ui-smoke/20261009-120000/first-run-title-bar/data"
            "-Dkkdd.disableCloudClis=true"
            "-Dkkdd.uiTestHooks=true"
            "-Dkubeconfig=/tmp/example-repo/composeApp/build/test-kubeconfig/empty.yaml"
            "-XX:+AllowEnhancedClassRedefinition"
        """.trimIndent()
        assertEquals(
            mapOf(
                "kkdd.dataDir" to "/tmp/example-repo/build/ui-smoke/20261009-120000/first-run-title-bar/data",
                "kkdd.disableCloudClis" to "true",
                "kkdd.uiTestHooks" to "true",
                "kubeconfig" to "/tmp/example-repo/composeApp/build/test-kubeconfig/empty.yaml",
            ),
            argfileProps(text),
        )
    }

    @Test
    fun `argfileProps reads unquoted lines and trims them`() {
        assertEquals(mapOf("a" to "1", "b.c" to "two"), argfileProps("-Da=1\n   -Db.c=two   \n"))
    }

    @Test
    fun `argfileProps skips lines that are not -D properties`() {
        val text = "-Xmx2g\n-XX:+UseG1GC\n\n   \nclasspath-entry\n-Dkept=yes\n--add-opens=java.base/java.lang=ALL-UNNAMED\n"
        assertEquals(mapOf("kept" to "yes"), argfileProps(text))
    }

    @Test
    fun `argfileProps strips quotes around a value`() {
        assertEquals("quoted value", argfileProps("\"-Dk=\"quoted value\"\"")["k"])
        assertEquals("single", argfileProps("-Dk='single'")["k"])
        assertEquals("\"unbalanced", argfileProps("-Dk=\"unbalanced")["k"])
        assertEquals("a'b\"", argfileProps("-Dk=a'b\"")["k"])
    }

    @Test
    fun `argfileProps keeps an equals sign inside a value`() {
        assertEquals("a=b=c", argfileProps("-Dk=a=b=c")["k"])
    }

    @Test
    fun `argfileProps unescapes doubled backslashes`() {
        assertEquals("""C:\example\dir""", argfileProps("""-Dpath=C:\\example\\dir""")["path"])
        assertEquals("""a\b""", argfileProps("""-Dpath=a\b""")["path"])
    }

    @Test
    fun `argfileProps lets the last line win`() {
        assertEquals("second", argfileProps("-Dk=first\n-Dk=second\n")["k"])
    }

    @Test
    fun `argfileProps gives an empty value to a property without equals`() {
        assertEquals(mapOf("flag" to ""), argfileProps("-Dflag"))
        assertEquals(mapOf("flag" to ""), argfileProps("-Dflag="))
    }

    @Test
    fun `argfileProps of nothing is empty`() {
        assertTrue(argfileProps("").isEmpty())
    }

    // ---------------------------------------------------------------- isolationProblems

    @Test
    fun `isolationProblems is empty for a demo-only throwaway run`() {
        assertEquals(emptyList(), isolationProblems(goodProps(), dataDir, emptyKubeconfig))
    }

    @Test
    fun `isolationProblems accepts the data directory by another spelling of its path`() {
        val spelled = File(dataDir, "../" + dataDir.name).path
        assertEquals(emptyList(), isolationProblems(goodProps() + ("kkdd.dataDir" to spelled), dataDir, emptyKubeconfig))
    }

    @Test
    fun `isolationProblems accepts a kubeconfig path that needs normalising`() {
        val kube = "/tmp/example-repo/composeApp/build/../build/test-kubeconfig/empty.yaml"
        assertEquals(emptyList(), isolationProblems(goodProps() + ("kubeconfig" to kube), dataDir, emptyKubeconfig))
    }

    @Test
    fun `isolationProblems names a kubeconfig that is not the empty one`() {
        val expected = listOf("-Dkubeconfig is not the empty test kubeconfig (build/test-kubeconfig/empty.yaml)")
        assertEquals(expected, isolationProblems(goodProps() + ("kubeconfig" to "/tmp/example-repo/fake/config"), dataDir, emptyKubeconfig))
        assertEquals(expected, isolationProblems(goodProps() + ("kubeconfig" to ""), dataDir, emptyKubeconfig))
        assertEquals(expected, isolationProblems(goodProps() - "kubeconfig", dataDir, emptyKubeconfig))
    }

    @Test
    fun `isolationProblems names another repository's empty kubeconfig and a look-alike name`() {
        val expected = listOf("-Dkubeconfig is not the empty test kubeconfig (build/test-kubeconfig/empty.yaml)")
        val elsewhere = "/tmp/other-repo/composeApp/build/test-kubeconfig/empty.yaml"
        assertEquals(expected, isolationProblems(goodProps() + ("kubeconfig" to elsewhere), dataDir, emptyKubeconfig))
        val glued = "/tmp/example-repo/composeApp/not-a-build/test-kubeconfig/empty.yaml"
        assertEquals(expected, isolationProblems(goodProps() + ("kubeconfig" to glued), dataDir, emptyKubeconfig))
    }

    @Test
    fun `isolationProblems names a data directory that is not the scenario's`() {
        val expected = listOf("-Dkkdd.dataDir is not this scenario's data directory")
        assertEquals(expected, isolationProblems(goodProps() + ("kkdd.dataDir" to otherDir.path), dataDir, emptyKubeconfig))
        assertEquals(expected, isolationProblems(goodProps() + ("kkdd.dataDir" to ""), dataDir, emptyKubeconfig))
        assertEquals(expected, isolationProblems(goodProps() - "kkdd.dataDir", dataDir, emptyKubeconfig))
    }

    @Test
    fun `isolationProblems names missing test hooks`() {
        val expected = listOf("-Dkkdd.uiTestHooks=true is missing")
        assertEquals(expected, isolationProblems(goodProps() - "kkdd.uiTestHooks", dataDir, emptyKubeconfig))
        assertEquals(expected, isolationProblems(goodProps() + ("kkdd.uiTestHooks" to "false"), dataDir, emptyKubeconfig))
    }

    @Test
    fun `isolationProblems names reachable cloud CLIs`() {
        val expected = listOf("-Dkkdd.disableCloudClis=true is missing: aws/gcloud would be reachable from the app")
        assertEquals(expected, isolationProblems(goodProps() - "kkdd.disableCloudClis", dataDir, emptyKubeconfig))
        assertEquals(expected, isolationProblems(goodProps() + ("kkdd.disableCloudClis" to "false"), dataDir, emptyKubeconfig))
    }

    @Test
    fun `isolationProblems lists every problem of an empty argfile in order`() {
        assertEquals(
            listOf(
                "-Dkubeconfig is not the empty test kubeconfig (build/test-kubeconfig/empty.yaml)",
                "-Dkkdd.dataDir is not this scenario's data directory",
                "-Dkkdd.uiTestHooks=true is missing",
                "-Dkkdd.disableCloudClis=true is missing: aws/gcloud would be reachable from the app",
            ),
            isolationProblems(emptyMap(), dataDir, emptyKubeconfig),
        )
    }

    // ---------------------------------------------------------------- parseStartedPid

    @Test
    fun `parseStartedPid finds the pid in the launch log`() {
        val log = """
            > Task :composeApp:hotRunDesktopAsync
            Started 'desktopMain' in background (12345)

            BUILD SUCCESSFUL in 3s
        """.trimIndent()
        assertEquals(12345L, parseStartedPid(log))
    }

    @Test
    fun `parseStartedPid is null without a Started line`() {
        assertNull(parseStartedPid("BUILD SUCCESSFUL in 3s"))
        assertNull(parseStartedPid(""))
        assertNull(parseStartedPid("Started 'desktopMain' in background (not-a-pid)"))
        assertNull(parseStartedPid("Started in background (123)"))
        // No app can be pid 0 or 1 (init).
        assertNull(parseStartedPid("Started 'desktopMain' in background (1)"))
        assertNull(parseStartedPid("Started 'desktopMain' in background (0)"))
    }

    // ---------------------------------------------------------------- parsePidFile

    @Test
    fun `parsePidFile reads the pid of a properties file`() {
        assertEquals(12345L, parsePidFile("#Fri Oct 09 12:00:00 UTC 2026\npid=12345\n"))
        assertEquals(12345L, parsePidFile("pid = 12345"))
        assertEquals(12345L, parsePidFile("# comment\n! another\nother=1\npid:12345"))
    }

    @Test
    fun `parsePidFile is null when there is no usable pid`() {
        assertNull(parsePidFile(""))
        assertNull(parsePidFile("# only a comment\n"))
        assertNull(parsePidFile("pid=\n"))
        assertNull(parsePidFile("pid=0\n"))
        assertNull(parsePidFile("pid=1\n"))
        assertNull(parsePidFile("pid=-1\n"))
        assertNull(parsePidFile("pid=abc\n"))
        assertNull(parsePidFile("other=1\n"))
    }
}
