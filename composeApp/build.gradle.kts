import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.spotless)
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
        val desktopMain by getting
        val desktopTest by getting {
            dependencies {
                implementation(libs.ktor.server.test.host)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
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

            implementation(libs.mcp.kotlin.sdk)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.sse)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.jna)
            implementation(libs.json.path)
            // jediterm 3.74 declares kotlin-stdlib 2.4.0 in its Gradle metadata but its
            // jars contain zero Kotlin classes; without the exclude, highest-wins would
            // raise the compile+runtime stdlib above the pinned 2.3.21 compiler.
            // (String notation: the KMP dependency handler has no configure-block
            // overload for catalog accessors, and catalog dependencies are immutable.)
            implementation("org.jetbrains.jediterm:jediterm-core:${libs.versions.jediterm.get()}") { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }
            implementation("org.jetbrains.jediterm:jediterm-ui:${libs.versions.jediterm.get()}") { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }
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

val generateVersionProperties by tasks.registering {
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

val generateScreenshots by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Drives the live app via WorkspaceManager and captures every Screen.Main + multi-tab + multi-window into docs/screenshots/. Runs on your Mac; the window must stay visible while it runs."
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

val generateEmptyKubeconfig by tasks.registering {
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
// is wiped before each run.
val testDataDir = layout.buildDirectory.dir("test-data").get().asFile

tasks.withType<Test>().configureEach {
    dependsOn(generateEmptyKubeconfig)
    environment("KUBECONFIG", emptyKubeconfig.get().asFile.absolutePath)
    // A local, so the doFirst lambda captures a plain File rather than the
    // build script (the configuration cache cannot serialise script objects).
    val dataDir = testDataDir
    systemProperty("kkdd.dataDir", dataDir.absolutePath)
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
    doFirst { dataDir.deleteRecursively() }
}
