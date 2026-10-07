pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle download the daemon JDK pinned in gradle/gradle-daemon-jvm.properties
// (Temurin 21, the same as CI) when no matching JDK is installed. The daemon's JDK
// compiles the code and is the runtime bundled into the installers. Regenerate with
// `./gradlew updateDaemonJvm --jvm-version 21 --jvm-vendor adoptium`. Foojay listed no
// Linux Temurin 21 when the file was generated (2026-10-07), so Linux has no download URL.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JediTerm (used by the in-app pod terminal; see
        // .docs/feature/pod-terminal-tab.md) is published only to JetBrains'
        // IntelliJ-dependencies repo, not Maven Central. Mirrors
        // container-dashboard's settings.gradle.kts.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

rootProject.name = "KubeKubeDashDash"
include(":composeApp")
