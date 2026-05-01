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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("spotlessApply")
}

kotlin {
    jvm("desktop")

    sourceSets.all {
        languageSettings.optIn("androidx.compose.foundation.ExperimentalFoundationApi")
        languageSettings.optIn("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    sourceSets {
        val desktopMain by getting

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
            implementation(libs.kotlinx.datetime)
            implementation(libs.logback.classic)
            implementation(libs.androidx.datastore.core)
            implementation(libs.androidx.datastore.preferences)

            implementation(libs.mcp.kotlin.sdk)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.sse)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.jna)
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
    jvmArgs("--add-opens=java.base/java.util=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "com.kubekubedashdash.MainKt"
        jvmArgs += "--add-opens=java.base/java.util=ALL-UNNAMED"

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
