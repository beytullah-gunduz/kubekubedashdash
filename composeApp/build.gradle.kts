import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.spotless)
    // Dev only (hotRunDesktop + hotMcpServer). Bundled with the Compose Multiplatform plugin, so no version here.
    id("org.jetbrains.compose.hot-reload")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_filename" to "disabled",
            ),
        )
    }
}

kotlin {
    jvm("desktop")

    sourceSets.all {
        languageSettings.optIn("androidx.compose.foundation.ExperimentalFoundationApi")
        languageSettings.optIn("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    sourceSets {
        val desktopMain = getByName("desktopMain")
        getByName("desktopTest") {
            dependencies {
                implementation(libs.ktor.server.test.host)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // runComposeUiTest: drawer-pane state retention (widescreen log panel plan).
                implementation(libs.compose.ui.test)
            }
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)

            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material3.adaptive.layout)
            implementation(libs.compose.material3.adaptive.navigation)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.fabric8.kubernetes.client)
            implementation(libs.fabric8.kubernetes.server.mock)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.logback.classic)
            implementation(libs.androidx.datastore.core)
            implementation(libs.androidx.datastore.preferences)

            // One Ktor version for every io.ktor module. Without the BOM, modules only
            // the MCP SDK pulls in (ktor-server-websockets) stay at the SDK's older Ktor.
            implementation(project.dependencies.platform(libs.ktor.bom))
            // Likewise one Kotlin version: ktor-server-core would otherwise keep
            // kotlin-reflect a release behind the stdlib.
            implementation(project.dependencies.platform(libs.kotlin.bom))
            implementation(libs.mcp.kotlin.sdk)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.sse)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.jna)
            implementation(libs.json.path)
            implementation(libs.jediterm.core)
            implementation(libs.jediterm.ui)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.kubekubedashdash.resources"
    generateResClass = always
}

val appVersion: String =
    project.findProperty("app.version")
        ?.toString()
        ?.removeSuffix("-SNAPSHOT")
        ?: "1.0.0"

val generateVersionProperties = tasks.register("generateVersionProperties") {
    val outputDir = layout.buildDirectory.dir("generated/resources/version")
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("version.properties").writeText("version=$version\n")
    }
}

kotlin.sourceSets.named("desktopMain") {
    resources.srcDir(generateVersionProperties.map { it.outputs.files.singleFile })
}

tasks.register<JavaExec>("generateScreenshots") {
    group = "documentation"
    description = "Drives the live app against the built-in demo cluster and writes the landing-page captures into build/screenshots/ (raw PNGs). Then run scripts/site_images.py."
    val desktopMain = kotlin.targets.getByName("desktop").compilations.getByName("main")
    dependsOn(desktopMain.compileTaskProvider)
    classpath(desktopMain.output.allOutputs, desktopMain.runtimeDependencyFiles)
    mainClass.set("com.kubekubedashdash.screenshots.GenerateScreenshotsKt")
    workingDir = rootProject.rootDir
}

compose.desktop {
    application {
        mainClass = "com.kubekubedashdash.MainKt"

        // ShellEnvironment.installIntoJvmEnv() reflects into java.lang.ProcessEnvironment
        // to inject an augmented PATH so subprocesses spawned by third-party libs
        // (notably fabric8's exec credential plugins for EKS/GKE auth) can find tools
        // like `aws` when the .app is launched from Finder, where the inherited PATH is
        // minimal. Without --add-opens this reflection fails on JDK 17+ and the exec
        // plugin returns "command not found" → every API call comes back 401.
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KubeKubeDashDash"
            packageVersion = appVersion
            modules("java.instrument", "java.naming", "java.net.http", "jdk.unsupported")

            macOS {
                iconFile.set(project.file("icons/icon.icns"))
            }
            windows {
                iconFile.set(project.file("icons/icon.ico"))
            }
            linux {
                iconFile.set(project.file("icons/icon_512.png"))
            }
        }

        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            // Shrinking only. Optimization requires a fully-resolvable class
            // hierarchy across every transitive jar, but we deliberately omit
            // optional Netty/fabric8 deps (log4j2, conscrypt, OpenSSL native
            // tcnative, jakarta.servlet, etc.) — the optimizer chokes on
            // missing superclasses even when the code paths are unreachable.
            optimize.set(false)
            // Pinned rather than inherited from the Compose plugin (1.12.1 defaults
            // to 7.8.0, which predates ProGuard's Kotlin 2.3 metadata support; 7.10.0
            // also covers Kotlin 2.4). Bump deliberately alongside Kotlin.
            version.set("7.10.0")
        }
    }
}

// ProGuard rewrites every class it passes through, -keep or not, but copies a
// signed jar's META-INF signature files across verbatim. The JVM then sees a
// signed jar whose classes no longer match their recorded digests and refuses to
// load any of them ("SHA-256 digest error for org/bouncycastle/..."). BouncyCastle
// is the only signed jar on the runtime classpath, and fabric8's MockWebServer
// references it directly since 7.7.0, so merely loading that class failed and
// every release build's demo cluster died on boot while `run` (no ProGuard) was
// fine. Dropping the signature files turns these into ordinary unsigned jars;
// nothing needs them signed (BouncyCastle is never registered as a JCE provider,
// and the bundled OpenJDK runtime does not demand signed providers anyway).
tasks.withType<AbstractProguardTask>().configureEach {
    // A local, so the doLast lambda captures a provider rather than the build
    // script (the configuration cache cannot serialise script objects).
    val outputDir = destinationDir
    doLast {
        val signatureFile = Regex("META-INF/(?:[^/]+\\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", RegexOption.IGNORE_CASE)
        outputDir.get().asFile.listFiles { file -> file.extension == "jar" }.orEmpty().forEach { jar ->
            val signatureEntries = ZipFile(jar).use { zip ->
                zip.entries().asSequence().map { it.name }.filter(signatureFile::matches).toList()
            }
            if (signatureEntries.isNotEmpty()) {
                FileSystems.newFileSystem(jar.toPath()).use { fs ->
                    signatureEntries.forEach { Files.delete(fs.getPath(it)) }
                }
                logger.lifecycle("Stripped stale signature files $signatureEntries from ${jar.name}")
            }
        }
    }
}

// Keep the test suite off the developer's real kubeconfig.
//
// `KubeConnectionManager.getCurrentContext()` falls through to the kubeconfig's
// `current-context` (read by KubeconfigReader: a plain parse, no exec plugin)
// whenever no mock connection is established, and a real connect goes through
// `Config.autoConfigure`, which reads ~/.kube/config and can invoke a kubeconfig
// `exec` plugin. That is real user state — a unit suite must never touch it.
// Measured before this override: 12 reads per `desktopTest` run, all from
// SessionViewModelHistoryTest, which builds a SessionViewModel against a
// never-connected manager.
//
// The app's KubeconfigLocator and fabric8 both honour $KUBECONFIG (after the
// `kubeconfig` system property, which the tests that need their own file set
// and restore), so pointing it at a generated empty file makes the fallback
// resolve to nothing instead of to the developer's clusters.
val emptyKubeconfig = layout.buildDirectory.file("test-kubeconfig/empty.yaml")

val generateEmptyKubeconfig = tasks.register("generateEmptyKubeconfig") {
    val output = emptyKubeconfig
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText("apiVersion: v1\nkind: Config\nclusters: []\ncontexts: []\nusers: []\n")
    }
}

// Likewise keep it off the developer's real preferences store and session
// file: SystemDirectories honours this property, so every DataStore-backed
// repository object and SessionStore.default() land in a build directory that
// is wiped before each run. SystemDirectories puts the logs directory inside it
// too, but nothing in a test JVM copies that into LOG_DIR (only main() does), so
// logback's app.log is pointed at the same place here.
val testDataDir = layout.buildDirectory.dir("test-data").get().asFile

tasks.withType<Test>().configureEach {
    dependsOn(generateEmptyKubeconfig)
    environment("KUBECONFIG", emptyKubeconfig.get().asFile.absolutePath)
    // A local, so the doFirst lambda captures a plain File rather than the
    // build script (the configuration cache cannot serialise script objects).
    val dataDir = testDataDir
    systemProperty("kkdd.dataDir", dataDir.absolutePath)
    systemProperty("LOG_DIR", dataDir.resolve("logs").absolutePath)
    doFirst { dataDir.deleteRecursively() }
}

// The screenshot generator is a JavaExec, not a Test: give it the same seam,
// with its own directory, so a run starts from default preferences, never opens
// the developer's preferences store (its theme flips persist; the session file is
// already covered by SessionPersistence.disable()), and lists no real kubeconfig
// context (F11). To verify without launching it: `--dry-run --no-configuration-cache`
// under an init script that prints the task's systemProperties and environment (a
// reused configuration-cache entry skips init-script callbacks).
val screenshotDataDir = layout.buildDirectory.dir("screenshot-data").get().asFile

tasks.named<JavaExec>("generateScreenshots") {
    dependsOn(generateEmptyKubeconfig)
    environment("KUBECONFIG", emptyKubeconfig.get().asFile.absolutePath)
    val dataDir = screenshotDataDir
    systemProperty("kkdd.dataDir", dataDir.absolutePath)
    // The generator's top-level logger starts logback before its main() sets LOG_DIR, so
    // the log directory SystemDirectories derives from kkdd.dataDir is set here as well.
    systemProperty("LOG_DIR", dataDir.resolve("logs").absolutePath)
    doFirst { dataDir.deleteRecursively() }
}

// Hot reload (dev only): the screenshot generator's demo-only seam. An empty kubeconfig lists
// no real context, so the Demo Cluster is the only cluster; a separate data directory
// (build/hot-run-data, reset by `clean`) keeps the developer's preferences, session, cluster
// colours and app.log untouched (main() points LOG_DIR at SystemDirectories.logsDirectory,
// which follows kkdd.dataDir). `hotMcpServerDesktop` then lets an agent drive this instance.
val hotRunDataDir = layout.buildDirectory.dir("hot-run-data").get().asFile
tasks.matching { it.name.startsWith("hotRun") }.configureEach {
    if (this is JavaExec) {
        dependsOn(generateEmptyKubeconfig)
        environment("KUBECONFIG", emptyKubeconfig.get().asFile.absolutePath)
        // The async launcher may not forward the task environment; the `kubeconfig` system
        // property wins over $KUBECONFIG in KubeconfigLocator and fabric8 alike, and rides the argfile.
        // -PhotRunKubeconfig=<file> swaps in a hand-made fake kubeconfig (e.g. one unreachable
        // context, to exercise the connection-error screen); never point it at a real one.
        systemProperty("kubeconfig", providers.gradleProperty("hotRunKubeconfig").getOrElse(emptyKubeconfig.get().asFile.absolutePath))
        systemProperty("kkdd.dataDir", hotRunDataDir.absolutePath)
    }
}

// Release-build verification. Nothing else ever runs the ProGuard-shrunk jars before a
// user does: every test runs unshrunk, and ProGuard itself stays silent about the
// breakage it causes. Both past outages were green on every other check — stale
// BouncyCastle signatures (see the signature strip above) killed the demo cluster, and
// a dropped direct interface made JobSupport fail verification so the app could not
// start (see the JobSupport keep in proguard-rules.pro). CI runs
// `verifyReleaseBuild` after the tests and before packaging an installer.
//
// The Compose plugin registers proguardReleaseJars and createRuntimeImage in
// afterEvaluate, so they are looked up inside these tasks' lazy configuration blocks.
val releaseCheckSources = layout.projectDirectory.dir("src/releaseCheck/java")

val releaseCheckTools = configurations.create("releaseCheckTools")
dependencies.add(releaseCheckTools.name, libs.asm)

val compileReleaseScan = tasks.register<JavaCompile>("compileReleaseScan") {
    source(releaseCheckSources.asFileTree.matching { include("**/IndirectSuperCallScan.java") })
    classpath = releaseCheckTools
    destinationDirectory.set(layout.buildDirectory.dir("release-check/scan-classes"))
    options.release.set(21)
}

val compileReleaseCanary = tasks.register<JavaCompile>("compileReleaseCanary") {
    val desktopMain = kotlin.targets.getByName("desktop").compilations.getByName("main")
    source(releaseCheckSources.asFileTree.matching { include("**/ReleaseCanary.java") })
    // Compiled against the unshrunk app; it runs against the shrunk one.
    classpath = files(desktopMain.output.allOutputs, desktopMain.runtimeDependencyFiles)
    destinationDirectory.set(layout.buildDirectory.dir("release-check/canary-classes"))
    options.release.set(21)
}

tasks.register<JavaExec>("scanReleaseJars") {
    group = "verification"
    description = "Fails if a ProGuard-shrunk release jar calls an interface method with invokespecial through an indirect superinterface, which the JVM verifier rejects."
    val proguard = tasks.named<AbstractProguardTask>("proguardReleaseJars")
    dependsOn(proguard)
    val shrunkJarsDir = proguard.flatMap { it.destinationDir }
    classpath(compileReleaseScan.flatMap { it.destinationDirectory }, releaseCheckTools)
    mainClass.set("com.kubekubedashdash.releasecheck.IndirectSuperCallScan")
    argumentProviders.add(CommandLineArgumentProvider { listOf(shrunkJarsDir.get().asFile.absolutePath) })
}

val releaseCanaryDir = layout.buildDirectory.dir("release-canary").get().asFile

// Runs ReleaseCanary against the shrunk jars with every state location pointed into a
// scratch sandbox that is wiped first: it must never read the developer's kubeconfig,
// preferences store, session file or logs.
fun JavaExec.runReleaseCanary(sandbox: File) {
    group = "verification"
    val proguard = tasks.named<AbstractProguardTask>("proguardReleaseJars")
    dependsOn(proguard, generateEmptyKubeconfig)
    // The shrunk jars first, so nothing else on the classpath can stand in for them.
    classpath(
        fileTree(proguard.flatMap { it.destinationDir }).matching { include("*.jar") },
        compileReleaseCanary.flatMap { it.destinationDirectory },
    )
    mainClass.set("com.kubekubedashdash.releasecheck.ReleaseCanary")
    workingDir = sandbox
    val home = sandbox.resolve("home")
    // A copy inside the sandbox, so the canary's guard can insist that KUBECONFIG is in there.
    val generatedKubeconfig = emptyKubeconfig.get().asFile
    val kubeconfig = sandbox.resolve("kubeconfig.yaml")
    environment("KUBECONFIG", kubeconfig.absolutePath)
    environment("HOME", home.absolutePath)
    systemProperty("kkdd.canary.sandbox", sandbox.absolutePath)
    systemProperty("user.home", home.absolutePath)
    systemProperty("java.io.tmpdir", sandbox.resolve("tmp").absolutePath)
    systemProperty("LOG_DIR", sandbox.resolve("logs").absolutePath)
    systemProperty("kkdd.dataDir", sandbox.resolve("data").absolutePath)
    systemProperty("java.awt.headless", "true")
    doFirst {
        sandbox.deleteRecursively()
        listOf("home", "tmp", "logs", "data").forEach { sandbox.resolve(it).mkdirs() }
        generatedKubeconfig.copyTo(kubeconfig)
    }
}

tasks.register<JavaExec>("releaseCanary") {
    description = "Boots the ProGuard-shrunk release jars headlessly on the full JDK and exercises logging, JSONPath, JediTerm, the demo cluster and an MCP session."
    runReleaseCanary(releaseCanaryDir.resolve("full-jdk"))
}

tasks.register<JavaExec>("releaseCanaryLimitedModules") {
    description = "Runs the release canary with the JDK limited to the modules jlink puts in the packaged app's runtime."
    runReleaseCanary(releaseCanaryDir.resolve("limited-modules"))
    val jlink = tasks.named<AbstractJLinkTask>("createRuntimeImage")
    val modules = jlink.flatMap { it.modules }
    val includeAllModules = jlink.flatMap { it.includeAllModules }
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            check(!includeAllModules.get()) { "includeAllModules is on: the packaged runtime has every module, so there is nothing to limit" }
            listOf("--limit-modules", modules.get().joinToString(","))
        },
    )
}

tasks.register("verifyReleaseBuild") {
    group = "verification"
    description = "Runs every check against the ProGuard-shrunk release jars."
    dependsOn("scanReleaseJars", "releaseCanary", "releaseCanaryLimitedModules")
}
