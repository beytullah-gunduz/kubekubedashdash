package com.kubekubedashdash

import java.util.Properties

object AppVersion {
    val version: String by lazy {
        val props = Properties()
        val stream = AppVersion::class.java.getResourceAsStream("/version.properties")
        if (stream != null) {
            props.load(stream)
            props.getProperty("version", "unknown")
        } else {
            "unknown"
        }
    }
}
