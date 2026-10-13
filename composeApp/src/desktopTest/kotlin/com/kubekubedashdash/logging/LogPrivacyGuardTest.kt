package com.kubekubedashdash.logging

import com.kubekubedashdash.ui.RetroShapeScanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the log-privacy rule (D5): a line logged at INFO, WARN or ERROR reaches the rolling `app.log`
 * (the FILE appender of logback.xml keeps INFO and above), which users attach to bug reports. Such a
 * line must not name a namespace, resource, kubeconfig context or cluster, host, URL, user, account,
 * project, profile or home path, and must not print a raw exception message or stack trace: fabric8's
 * messages carry the API URL, which holds the server and the namespace. Log a failure as
 * `describeFailureShape(e)` (`HTTP 403` or the root cause's class name) and put the names, the raw
 * message or the throwable on an added `log.debug(...)` line, which stays out of `app.log`.
 *
 * A call is flagged when it interpolates into a string (TEMPLATE), logs an exception message or a
 * stack trace (MESSAGE), passes an argument or concatenates code into its message named like an
 * identifier from the D5 list (IDENTIFIER: a word such as `namespace`, `host`, `msg`, `projectId` or
 * `file`, singular or plural, or a camelCase name ending in one, such as `displayLabel` or
 * `kubeconfigPath`; `simpleName` and a `.size` or `.length` are fine), or passes a throwable, its
 * cause or a Result's failure, or concatenates one into the message (THROWABLE). A call that is safe
 * anyway ends its line with `// log-safe: <reason>`; the reason is mandatory, and the marker excuses
 * only the call that ends that line. The scan covers every main source except the dev-only demo and
 * screenshot ones, which log fixtures only ([LogPrivacyScanner.EXEMPT]).
 *
 * Known blind spots, left to review: a message built in a variable first (`val msg = ...; log.warn(msg)`);
 * an argument whose `toString()` prints names although its variable is not named like one; a value looked
 * up by a literal key (`System.getProperty("user.home")`); `\$` in a raw string, which still interpolates;
 * and calls the receiver pattern does not see (`with(log) { warn(...) }`, `log.atWarn()`,
 * `LoggerFactory.getLogger(...).warn(...)`).
 *
 * The scan masks strings, char literals and comments first ([RetroShapeScanner.mask]), so a log call
 * quoted in a comment is not a call and a `)` inside a string never unbalances one. The canary tests
 * pin the scanner on synthetic sources, so a broken scanner cannot make the repo scan pass vacuously.
 * Read-only: it never writes anything.
 */
class LogPrivacyGuardTest {

    @Test
    fun `no INFO WARN or ERROR line in the scanned sources names an identifier or prints a raw exception`() {
        val root = mainRoot()
        assertTrue(root.isDirectory, "main source root not found: ${root.path}")
        val files = allFiles(root)
        assertTrue(files.isNotEmpty(), "the scan covers no file")

        val violations = files.flatMap { (file, relative) -> LogPrivacyScanner.scan(relative, file.readText()) }
        if (violations.isNotEmpty()) fail(HEADER + "\n" + LogPrivacyScanner.report(violations))
    }

    @Test
    fun `the widened scan walks main and skips the exempt sources`() {
        val root = mainRoot()
        val relatives = allFiles(root).map { it.second }
        assertTrue(relatives.size >= 250, "suspiciously few main sources (${relatives.size}) under ${root.path}")
        assertTrue("util/KubeClient.kt" in relatives)
        assertFalse(relatives.any { it.startsWith("screenshots/") }, "screenshots/ is exempt")
        LogPrivacyScanner.EXEMPT.filter { !it.endsWith("/") }.forEach { assertFalse(it in relatives, "$it is exempt") }
    }

    @Test
    fun `every exemption names a real source`() {
        val root = mainRoot()
        LogPrivacyScanner.EXEMPT.forEach { exempt ->
            val target = root.resolve(exempt)
            assertTrue(if (exempt.endsWith("/")) target.isDirectory else target.isFile, "stale exemption: $exempt")
        }
    }

    @Test
    fun `an exempt path is not scanned`() {
        assertEquals(
            listOf("util/KubeClient.kt", "util/Foo.kt"),
            LogPrivacyScanner.selectScanned(
                listOf(
                    "screenshots/Gen.kt",
                    "screenshots/deep/Shot.kt",
                    "util/KubeClient.kt",
                    "util/MockClusterProvider.kt",
                    "util/MockClusterSeed.kt",
                    "util/MockHelmReleases.kt",
                    "util/DemoClusterSimulator.kt",
                    "util/DemoPrometheusDispatcher.kt",
                    "util/Foo.kt",
                ),
            ),
        )
    }

    @Test
    fun `a string template fires at every level`() {
        assertEquals(listOf(1 to "TEMPLATE"), hits("log.info(\"hello ${D}x\")"))
        assertEquals(listOf(1 to "TEMPLATE"), hits("log.warn(\"hello $D{x.size}\")"))
        assertEquals(listOf(1 to "TEMPLATE"), hits("log.error(\"hello ${D}x\")"))
        assertEquals(listOf(1 to "TEMPLATE"), hits("log.info(\"x {}\", '$D')"))
    }

    @Test
    fun `an exception message or stack trace fires`() {
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.message)"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", it.localizedMessage)"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.stackTrace)"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.stackTraceToString())"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.toString())"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.message ?: \"none\")"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.message?.take(80))"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.message )"))
        assertEquals(emptyList(), hits("log.warn(\"f {}\", e.messageCount)"))
        assertEquals(emptyList(), hits("log.warn(\"f {}\", messages.size)"))
        assertEquals(emptyList(), hits("log.warn(\"f {}\", describeFailureShape(e))"))
    }

    @Test
    fun `an argument named like an identifier fires`() {
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"x {}\", namespace)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.info(\"a {} b {}\", count, pod.name)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.error(\"x {}\", describe(context))"))
        for (word in listOf("ns", "podName", "nodeName", "ctx", "contextName", "cluster", "clusterName", "host", "server", "url", "uri", "endpoint", "user", "account", "project", "profile", "path", "label")) {
            assertEquals(listOf(1 to "IDENTIFIER"), hits("log.info(\"x {}\", $word)"), word)
        }
        for (word in listOf("namespaces", "names", "contexts", "clusters", "hosts", "paths", "msg", "message", "stderr", "stdout", "output")) {
            assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"x {}\", $word)"), word)
        }
        assertEquals(emptyList(), hits("log.info(\"namespace {} name {}\", count, size)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"Failed for \" + namespace)"))
        assertEquals(listOf(2 to "IDENTIFIER"), hits(src("val a = 1", "log.warn(", "    \"Failed for \" + pod.name,", ")")))
        assertEquals(emptyList(), hits("log.info(\"x {}\", nameCount)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", coroutineContext)"))
        assertEquals(emptyList(), hits("log.info(\"Deleting a pod\")"))
    }

    @Test
    fun `a camelCase name ending in an identifier word fires`() {
        for (word in listOf("session.displayLabel", "nsLabel", "kubeconfigPath", "errorMessage", "contextName", "pod.hostName", "nsNames", "roots.labelPaths", "cfg.apiUrl", "req.targetUri", "apiEndpoint", "gcpProject", "awsProfile", "currentUser", "cliOutput", "defaultNamespace", "defaultNamespaces", "activeContext", "serverHost", "billingAccount", "tags.podLabels", "dirPaths")) {
            assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"x {}\", $word)"), word)
        }
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"x {}\", msg)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"x {}\", paths.map { it })"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"x {}\", count, session.displayLabel)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"Failed for \" + kubeconfigPath)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits(src("log.warn(", "    \"x {}\",", "    stderr.trim(),", ")")))
    }

    @Test
    fun `a class name and a plain suffix lookalike do not fire`() {
        assertEquals(emptyList(), hits("log.warn(\"x {}\", it::class.simpleName)"))
        assertEquals(emptyList(), hits("log.warn(\"x {}\", e.javaClass.simpleName)"))
        assertEquals(emptyList(), hits("log.warn(\"x {}\", type.qualifiedName)"))
        assertEquals(emptyList(), hits("log.warn(\"x {}\", type.canonicalName)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", coroutineContext)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", pathCount)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", messageCount)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", outputs.size)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", Name)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", sessionId)"))
    }

    @Test
    fun `plurals, ids, files and credentials fire`() {
        for (word in listOf(
            "profiles", "projects", "users", "urls", "endpoints", "accounts", "outputs", "messages", "hosts", "servers",
            "awsProfiles", "gcpProjects", "errorMessages", "serverUrls", "kubeContexts", "nodeHosts",
            "userId", "projectId", "gcpProjectId", "accountIds", "email", "accessToken", "clusterArn",
            "file", "configFile", "dir", "outputDir", "directory", "home", "userHome", "kubeconfig",
        )) {
            assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"x {}\", $word)"), word)
        }
    }

    @Test
    fun `a size or a count of names is a number`() {
        assertEquals(emptyList(), hits("log.info(\"x {}\", namespaces.size)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", target.namespaces.size)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", name.length)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", paths?.size)"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", users.count())"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", hosts.isEmpty())"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.info(\"x {} {}\", namespaces.size, namespaces)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.info(\"x {}\", namespaces.first())"))
    }

    @Test
    fun `an exception message is the message rule and not also an identifier`() {
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.message)"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.localizedMessage)"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"f {}\", e.message?.take(80))"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"f {}\", result.message.orEmpty())"))
        assertEquals(listOf(1 to "MESSAGE", 1 to "IDENTIFIER"), hits("log.warn(\"f {}\", e.message, msg)"))
    }

    @Test
    fun `a bare throwable last fires`() {
        assertEquals(listOf(1 to "THROWABLE"), hits("log.error(\"x\", e)"))
        assertEquals(listOf(1 to "THROWABLE"), hits("log.warn(\"x {}\", count, t)"))
        assertEquals(listOf(1 to "THROWABLE"), hits("log.warn(\"x\", *args, cause)"))
        assertEquals(listOf(1 to "THROWABLE"), hits("runCatching { f() }.onFailure { log.warn(\"x\", it) }"))
        for (word in listOf("e", "t", "ex", "err", "cause", "throwable", "error", "exception", "failure", "it")) {
            assertEquals(listOf(1 to "THROWABLE"), hits("log.warn(\"x\", $word)"), word)
        }
        assertEquals(listOf(2 to "THROWABLE"), hits(src("val a = 1", "log.warn(", "  \"x\",", "  e,", ")")))
        assertEquals(emptyList(), hits("log.warn(\"x {}\", errorCount)"))
        assertEquals(emptyList(), hits("log.warn(\"x {}\", e.javaClass.simpleName)"))
        assertEquals(emptyList(), hits("log.warn(\"x {}\", describeFailureShape(e))"))
    }

    @Test
    fun `a throwable anywhere in the arguments, its cause or a Result's failure fires`() {
        assertEquals(listOf(1 to "THROWABLE"), hits("log.warn(\"x {} {}\", e, count)"))
        assertEquals(listOf(1 to "THROWABLE"), hits("log.warn(\"x {}\", (e))"))
        assertEquals(listOf(1 to "THROWABLE"), hits("log.warn(\"x {}\", e as Throwable)"))
        assertEquals(listOf(1 to "THROWABLE"), hits("log.warn(\"x {}\", e.cause)"))
        assertEquals(listOf(1 to "THROWABLE"), hits("log.warn(\"x {}\", result.exceptionOrNull())"))
        assertEquals(listOf(1 to "THROWABLE"), hits("log.warn(\"Failed: \" + e)"))
        assertEquals(listOf(1 to "THROWABLE"), hits(src("log.error(", "    \"Failed: \" +", "        exception,", ")")))
        assertEquals(emptyList(), hits("log.warn(\"Failed: {}\", count + extra)"))
        assertEquals(emptyList(), hits("log.warn(\"Failed: \" + e.javaClass.simpleName)"))
        assertEquals(emptyList(), hits("log.warn(\"Failed: {}\", describeFailureShape(e.cause ?: e))"))
    }

    @Test
    fun `a stack trace fires wherever it goes next`() {
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"x {}\", e.stackTraceToString().take(200))"))
        assertEquals(listOf(1 to "MESSAGE"), hits("log.warn(\"x {}\", e.stackTrace.joinToString())"))
    }

    @Test
    fun `a call spread over lines is read whole and reported at its first line`() {
        val source = src(
            "val ok = 1",
            "log.warn(",
            "    \"Failed {}\",",
            "    namespace,",
            ")",
        )
        assertEquals(listOf(2 to "IDENTIFIER"), hits(source))
    }

    @Test
    fun `a paren inside a string does not unbalance the call`() {
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.warn(\"a) b {}\", namespace)"))
        assertEquals(emptyList(), hits("log.info(\"(\", count)"))
        assertEquals(listOf(1 to "IDENTIFIER", 2 to "TEMPLATE"), hits(src("log.warn(\"a) {}\", name)", "log.info(\"b ${D}x\")")))
    }

    @Test
    fun `a dollar in a raw string fires`() {
        assertEquals(listOf(1 to "TEMPLATE"), hits("log.info(${TQ}a ${D}b$TQ)"))
        assertEquals(listOf(1 to "TEMPLATE"), hits(src("log.info(${TQ}a", "b $D{c}$TQ)")))
    }

    @Test
    fun `an escaped dollar does not fire and an escaped backslash does not escape it`() {
        assertEquals(emptyList(), hits("log.info(\"cost \\${D}5\")"))
        assertEquals(listOf(1 to "TEMPLATE"), hits("log.info(\"cost \\\\${D}x\")"))
        assertEquals(emptyList(), hits("log.info(\"cost \\\\\\${D}5\")"))
    }

    @Test
    fun `a safe argument ends its last line with a marker and a reason`() {
        assertEquals(emptyList(), hits("log.info(\"x {}\", name) // log-safe: key of a fixed enum"))
        assertEquals(emptyList(), hits("log.info(\"x {}\", name) //log-safe: key of a fixed enum"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.info(\"x {}\", name) // log-safe:"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.info(\"x {}\", name) // log safe: reason"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.info(\"x {}\", name) // reason"))
        assertEquals(
            emptyList(),
            hits(src("log.info(", "    \"x {}\",", "    name,", ") // log-safe: key of a fixed enum")),
        )
        assertEquals(
            listOf(1 to "IDENTIFIER"),
            hits(src("log.info( // log-safe: only the first line", "    \"x {}\",", "    name,", ")")),
        )
    }

    @Test
    fun `a marker excuses only the call that ends its line`() {
        assertEquals(
            listOf(1 to "IDENTIFIER"),
            hits("log.warn(\"a {}\", namespace); log.info(\"b {}\", host) // log-safe: the second only"),
        )
        assertEquals(
            listOf(1 to "IDENTIFIER"),
            hits("if (ok) log.warn(\"a {}\", namespace) else log.warn(\"b {}\", name) // log-safe: the second only"),
        )
        assertEquals(emptyList(), hits("runCatching { f() }.onFailure { log.warn(\"x {}\", name) } // log-safe: a fixed key"))
    }

    @Test
    fun `a marker inside a string does not exempt the call`() {
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.info(\"x {} // log-safe: nope\", name)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log.info(\"x {}\", name + \"// log-safe: nope\")"))
    }

    @Test
    fun `a dollar in a comment does not fire`() {
        assertEquals(emptyList(), hits("log.info(\"x\") // costs ${D}5"))
        assertEquals(emptyList(), hits(src("log.info(", "    \"x {}\", // ${D}price", "    count,", ")")))
        assertEquals(emptyList(), hits(src("log.info(", "    \"x {}\", /* ${D}price */", "    count,", ")")))
    }

    @Test
    fun `a log call quoted in a comment is not a call`() {
        assertEquals(emptyList(), hits("// log.warn(\"x {}\", namespace)"))
        assertEquals(emptyList(), hits("/* log.error(\"x\", e) */"))
        assertEquals(
            emptyList(),
            hits(
                src(
                    "/**",
                    " * Logs with log.warn(\"x ${D}y {}\", e.message) when it fails.",
                    " */",
                    "fun f() = Unit",
                ),
            ),
        )
        assertEquals(emptyList(), hits("val s = \"log.warn(\\\"x {}\\\", namespace)\""))
    }

    @Test
    fun `other logger receivers fire and lookalikes do not`() {
        assertEquals(listOf(1 to "THROWABLE"), hits("crashLogger.error(\"Uncaught exception\", throwable)"))
        assertEquals(listOf(1 to "MESSAGE"), hits("shutdownLog.warn(\"stop failed: {}\", it.message)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("this.log.warn(\"x {}\", ns)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("logger.info(\"x {}\", host)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("LoggerFactory.getLogger(\"x\").also { it.log.warn(\"x {}\", host) }"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("LOG.warn(\"x {}\", host)"))
        assertEquals(listOf(1 to "IDENTIFIER"), hits("log?.warn(\"x {}\", host)"))
        assertEquals(emptyList(), hits("catalog.warn(\"x {}\", name)"))
        assertEquals(emptyList(), hits("blog.warn(\"x {}\", name)"))
        assertEquals(emptyList(), hits("Logger.warn(\"x {}\", name)"))
        assertEquals(emptyList(), hits("val text = withC.tailingLines(tailLines).log"))
    }

    @Test
    fun `debug and trace calls may name anything`() {
        assertEquals(emptyList(), hits("log.debug(\"Fetching pod={} namespace={}: {}\", name, namespace, e.message)"))
        assertEquals(emptyList(), hits("log.debug(\"Failed ${D}name\", e)"))
        assertEquals(emptyList(), hits("log.trace(\"Event {} in {}\", obj.metadata?.name, namespace)"))
    }

    @Test
    fun `a call without arguments after the message is clean`() {
        assertEquals(emptyList(), hits("log.info(\"Triggering a CronJob\")"))
        assertEquals(emptyList(), hits("log.warn(\"Cluster topology graph truncated at {} visible nodes\", limit)"))
        assertEquals(emptyList(), hits("log.warn(\"Metrics read '{}' {}\", read, outcome)"))
    }

    @Test
    fun `an unterminated call reports and does not throw`() {
        assertEquals(listOf(1 to "UNTERMINATED"), hits("log.warn(\"x {}\", namespace"))
        assertEquals(listOf(2 to "UNTERMINATED"), hits(src("val a = 1", "log.info(")))
        assertEquals(listOf(1 to "UNTERMINATED"), hits("log.error("))
    }

    @Test
    fun `an unterminated call is not excused by a marker`() {
        assertEquals(listOf(1 to "UNTERMINATED"), hits("log.warn(\"x {}\", name // log-safe: reason"))
    }

    @Test
    fun `one call can break several rules and each is reported`() {
        assertEquals(
            listOf(1 to "TEMPLATE", 1 to "MESSAGE", 1 to "IDENTIFIER"),
            hits("log.warn(\"x ${D}y {}\", e.message, namespace)"),
        )
    }

    @Test
    fun `violations come back sorted by line and the report sorts by file then line`() {
        val source = src(
            "log.warn(\"a {}\", e.message)",
            "log.info(\"b ${D}x\")",
            "log.error(\"c\", e)",
        )
        val lines = LogPrivacyScanner.scan("A.kt", source).map { it.line }
        assertEquals(lines.sorted(), lines)
        assertEquals(listOf(1, 2, 3), lines)

        val shuffled = listOf(
            LogPrivacyScanner.Violation("b.kt", 2, "MESSAGE", "h"),
            LogPrivacyScanner.Violation("a.kt", 9, "IDENTIFIER", "h"),
            LogPrivacyScanner.Violation("a.kt", 3, "TEMPLATE", "h"),
        )
        assertEquals("a.kt:3 TEMPLATE\na.kt:9 IDENTIFIER\nb.kt:2 MESSAGE", LogPrivacyScanner.report(shuffled))
    }

    private fun hits(source: String): List<Pair<Int, String>> = LogPrivacyScanner.scan("Sample.kt", source).map { it.line to it.rule }

    private fun src(vararg lines: String): String = lines.joinToString("\n")

    /** Gradle runs tests from the composeApp project directory; an IDE run starts at the repo root. */
    private fun mainRoot(): File {
        val fromProject = File("src/desktopMain/kotlin")
        val kotlinRoot = if (fromProject.isDirectory) fromProject else File("composeApp/src/desktopMain/kotlin")
        return kotlinRoot.resolve(MAIN_PACKAGE)
    }

    /** Every main source minus the exempt ones. */
    private fun allFiles(root: File): List<Pair<File, String>> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it to it.relativeTo(root).invariantSeparatorsPath }
        .filter { (_, relative) -> LogPrivacyScanner.selectScanned(listOf(relative)).isNotEmpty() }
        .toList()

    private companion object {
        const val MAIN_PACKAGE = "com/kubekubedashdash"
        const val D = "$"
        const val TQ = "\"\"\""

        const val HEADER =
            "An INFO, WARN or ERROR log line reaches app.log, which users attach to bug reports (D5). " +
                "It must not name a namespace, resource, context, cluster, host, URL, user, account, project, " +
                "profile or path, and must not print a raw exception (fabric8 messages carry the API URL). " +
                "Log a failure as describeFailureShape(e) and move the names, the raw message or the throwable " +
                "to a log.debug line. A call that is safe anyway ends its last line with \"// log-safe: <reason>\". " +
                "Rules: TEMPLATE (string template), MESSAGE (exception message or stack trace), " +
                "IDENTIFIER (argument named like an identifier: a word such as namespace or msg, or a camelCase name ending in Name, Path, Label, Message and the like), THROWABLE (a throwable, its cause or a Result's failure as an argument or concatenated into the message), " +
                "UNTERMINATED (call the guard cannot read)."
    }
}

/**
 * The text scanner behind [LogPrivacyGuardTest]. It masks strings, char literals and comments to
 * spaces ([RetroShapeScanner.mask], offsets and newlines preserved), finds each INFO/WARN/ERROR call
 * on the masked text, takes it to its balancing `)`, and runs the rules on it. It is a scanner, not a
 * parser: it must never throw, whatever the input.
 */
internal object LogPrivacyScanner {

    data class Violation(val file: String, val line: Int, val rule: String, val hint: String)

    /**
     * Sources exempt from the scan, relative to the main package; an entry ending in `/` is a directory.
     * They log demo and screenshot fixtures only.
     */
    val EXEMPT = listOf(
        "screenshots/",
        "util/MockClusterProvider.kt",
        "util/MockClusterSeed.kt",
        "util/MockHelmReleases.kt",
        "util/DemoClusterSimulator.kt",
        "util/DemoPrometheusDispatcher.kt",
    )

    private const val TEMPLATE = "TEMPLATE"
    private const val MESSAGE = "MESSAGE"
    private const val IDENTIFIER = "IDENTIFIER"
    private const val THROWABLE = "THROWABLE"
    private const val UNTERMINATED = "UNTERMINATED"

    private val callStart = Regex("""\b(?:log|logger|LOG|LOGGER|[a-z][A-Za-z0-9]*(?:Log|Logger))\s*\??\.\s*(?:info|warn|error)\s*\(""")

    // `?:`, `?.` and `!!` after the access catch `e.message ?: "x"` and `e.message?.take(80)` too. The
    // lookahead leaves the delimiter out of the match, so [blankMessageAccess] can blank the access alone.
    // A stack trace is one wherever it goes next (`e.stackTraceToString().take(200)`).
    private val messageAccess =
        Regex("""\.(?:stackTrace|stackTraceToString)\b|\.(?:message|localizedMessage|toString)\b(?=\s*(?:\(\s*\))?\s*(?:[,)]|\?[:.]|!!))""")

    // Plain words, then camelCase names ending in a D5 word, each also plural. Class names are fine (R1:
    // `e.javaClass.simpleName`), and `coroutineContext` is a coroutine scope element, not a kubeconfig context.
    private val identifierWord = Regex(
        """\b(?:namespaces?|ns|names?|podNames?|nodeNames?|contexts?|ctx|contextNames?|clusters?|clusterNames?|hosts?|servers?|urls?|uris?|endpoints?|users?|accounts?|projects?|profiles?|paths?|labels?|msgs?|messages?|stderr|stdout|outputs?|userIds?|accountIds?|projectIds?|profileIds?|emails?|tokens?|arns?|files?|dirs?|director(?:y|ies)|home|kubeconfigs?)\b""" +
            """|\b(?!(?:simpleName|qualifiedName|canonicalName|coroutineContext)\b)[a-z][A-Za-z0-9]*(?:Names?|Paths?|Labels?|Namespaces?|Contexts?|Hosts?|Urls?|Uris?|Endpoints?|Accounts?|Projects?|Profiles?|Users?|Messages?|Outputs?|AccountIds?|ProjectIds?|UserIds?|ProfileIds?|Emails?|Tokens?|Arns?|Files?|Dirs?|Director(?:y|ies)|Home|Kubeconfigs?)\b""",
    )

    // A size or a count is a number, whatever it counts (`namespaces.size`, `name.length`).
    private val countAccess = Regex(
        """\b[A-Za-z_][A-Za-z0-9_]*(?:\s*(?:\?\.|!!\.|\.)\s*[A-Za-z_][A-Za-z0-9_]*)*\s*(?:\?\.|!!\.|\.)\s*(?:(?:size|length)\b|(?:count|isEmpty|isNotEmpty)\s*\(\s*\))""",
    )

    private val throwableNames = setOf("e", "t", "ex", "err", "cause", "throwable", "error", "exception", "failure", "it")

    // A throwable concatenated into the message prints its class and message: `"Failed: " + e`.
    private val concatenatedThrowable = Regex("""\+\s*(?:${throwableNames.joinToString("|")})\b(?!\s*[.(\[])""")

    // What a throwable argument looks like besides its bare name: its cause, or a Result's failure.
    private val throwableAccess = Regex("""(?:\.cause|\bexceptionOrNull\s*\(\s*\))$""")

    private val safeMarker = Regex("""//\s*log-safe:\s*\S""")

    private val hints = mapOf(
        TEMPLATE to "log with {} placeholders, never a string template",
        MESSAGE to "log describeFailureShape(e); the raw message names the request URL",
        IDENTIFIER to "INFO, WARN and ERROR must not name a resource: keep the action, move the names to a log.debug line",
        THROWABLE to "log describeFailureShape(e) and put the throwable on a log.debug line",
        UNTERMINATED to "the call never closes, so the guard cannot read it",
    )

    /** [paths] (relative to the main package) without the exempt ones, in order. */
    fun selectScanned(paths: List<String>): List<String> = paths.filterNot { path ->
        EXEMPT.any { exempt -> if (exempt.endsWith("/")) path.startsWith(exempt) else path == exempt }
    }

    /** One `file:line RULE` per violation, sorted by file then line. */
    fun report(violations: List<Violation>): String = violations
        .sortedWith(compareBy({ it.file }, { it.line }))
        .joinToString("\n") { "${it.file}:${it.line} ${it.rule}" }

    fun scan(file: String, source: String): List<Violation> {
        // Strings and comments blanked: calls, parens, arguments. With comments kept, only strings are
        // blanked, so a marker inside a string never exempts and a `$` in a comment never fires.
        val masked = RetroShapeScanner.mask(source)
        val withComments = RetroShapeScanner.mask(source, keepComments = true)
        val lineStarts = lineStarts(masked)

        fun lineOf(offset: Int): Int {
            val found = lineStarts.binarySearch(offset)
            return (if (found >= 0) found else -found - 2) + 1
        }

        fun lineText(line: Int): String {
            val start = lineStarts[line - 1]
            val end = if (line < lineStarts.size) lineStarts[line] - 1 else withComments.length
            return withComments.substring(start, end)
        }

        val violations = mutableListOf<Violation>()
        for (match in callStart.findAll(masked)) {
            val line = lineOf(match.range.first)
            val open = match.range.last
            val close = closeParen(masked, open)
            if (close < 0) {
                violations += violation(file, line, UNTERMINATED)
                continue
            }
            // The marker excuses the call that ends its line, not another call before it on that line.
            if (safeMarker.containsMatchIn(lineText(lineOf(close))) && endsItsLine(masked, close)) continue

            val arguments = splitArguments(masked.substring(open + 1, close))
            // `e.message` is the MESSAGE rule's, so it must not also count as the word `message` here.
            val identifierCall = blankCountAccess(blankMessageAccess(masked.substring(open, close + 1)))
            val identifierArguments = splitArguments(identifierCall.substring(1, identifierCall.length - 1))
            if (hasTemplate(source, withComments, open, close)) violations += violation(file, line, TEMPLATE)
            if (messageAccess.containsMatchIn(masked.substring(open, close + 1))) violations += violation(file, line, MESSAGE)
            // The message too: its literals are blanked, so only code concatenated into it (`"x " + name`) can match.
            if (identifierArguments.any { identifierWord.containsMatchIn(it) }) violations += violation(file, line, IDENTIFIER)
            if (arguments.drop(1).any(::isThrowableLike) || arguments.any { concatenatedThrowable.containsMatchIn(it) }) {
                violations += violation(file, line, THROWABLE)
            }
        }
        return violations.sortedBy { it.line }
    }

    private fun violation(file: String, line: Int, rule: String) = Violation(file, line, rule, hints.getValue(rule))

    private fun blankMessageAccess(call: String): String = messageAccess.replace(call) { " ".repeat(it.value.length) }

    private fun blankCountAccess(call: String): String = countAccess.replace(call) { " ".repeat(it.value.length) }

    // A bare throwable, in parentheses or cast (`(e)`, `e as Throwable`), its cause or a Result's failure.
    private fun isThrowableLike(argument: String): Boolean {
        var a = argument.trim().removePrefix("*").trim()
        while (a.startsWith("(") && a.endsWith(")")) a = a.substring(1, a.length - 1).trim()
        a = a.replace(Regex("""\s+as\??\s+[A-Za-z_][A-Za-z0-9_.]*\??$"""), "").trim()
        return a in throwableNames || throwableAccess.containsMatchIn(a)
    }

    /** True when nothing but closing brackets, `;` or `,` follows [close] on its line in the masked text. */
    private fun endsItsLine(masked: String, close: Int): Boolean {
        val end = masked.indexOf('\n', close).let { if (it < 0) masked.length else it }
        return masked.substring(close + 1, end).all { it.isWhitespace() || it in ";,)}]" }
    }

    /** True when [source] holds an unescaped `$` between [open] and [close] that sits inside a string or char literal. */
    private fun hasTemplate(source: String, withComments: String, open: Int, close: Int): Boolean {
        for (index in open..close) {
            // Blanked by the mask with comments kept: a string or char literal, not a comment.
            if (source[index] != '$' || withComments[index] == '$') continue
            var backslashes = 0
            while (index - 1 - backslashes >= 0 && source[index - 1 - backslashes] == '\\') backslashes++
            if (backslashes % 2 == 0) return true
        }
        return false
    }

    private fun lineStarts(text: String): IntArray {
        val starts = mutableListOf(0)
        text.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    /** Index of the `)` matching the `(` at [open], or -1 when the call never closes. */
    private fun closeParen(masked: String, open: Int): Int {
        var depth = 0
        for (index in open until masked.length) {
            when (masked[index]) {
                '(' -> depth++

                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    /** The top-level arguments of a call body, trimmed; a trailing comma adds no argument. */
    private fun splitArguments(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in body) {
            when {
                c == ',' && depth == 0 -> {
                    parts += current.toString().trim()
                    current.setLength(0)
                }

                else -> {
                    when (c) {
                        '(', '{', '[' -> depth++
                        ')', '}', ']' -> if (depth > 0) depth--
                    }
                    current.append(c)
                }
            }
        }
        parts += current.toString().trim()
        return if (parts.size > 1 && parts.last().isEmpty()) parts.dropLast(1) else parts
    }
}
