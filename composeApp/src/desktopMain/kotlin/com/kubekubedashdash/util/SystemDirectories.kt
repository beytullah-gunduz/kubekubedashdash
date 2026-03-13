package com.kubekubedashdash.util

object SystemDirectories {
    val applicationDirectory: String =
        if (System.getenv("APPDATA") != null) {
            System.getenv("APPDATA") + "/KubeKubeDashDash"
        } else {
            System.getProperty("user.home") + "/.KubeKubeDashDash"
        }
}
