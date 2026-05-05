package com.kubekubedashdash.util

import org.slf4j.LoggerFactory
import java.io.File

data class AwsProfile(
    val name: String,
    val defaultRegion: String?,
    val source: Source,
) {
    enum class Source { CREDENTIALS, CONFIG, BOTH }
}

object AwsProfileReader {

    private val log = LoggerFactory.getLogger(AwsProfileReader::class.java)
    private val PROFILE_NAME_REGEX = Regex("^[A-Za-z0-9_-]+$")

    fun listProfiles(): List<AwsProfile> {
        val home = System.getProperty("user.home")
        val credentials = parseIni(File("$home/.aws/credentials"))
        val config = parseIni(File("$home/.aws/config"))

        val configProfiles: Map<String, Map<String, String>> = config.mapKeys { (section, _) ->
            when {
                section == "default" -> "default"
                section.startsWith("profile ") -> section.removePrefix("profile ").trim()
                else -> section
            }
        }

        val names = (credentials.keys + configProfiles.keys)
            .filter { name ->
                if (PROFILE_NAME_REGEX.matches(name)) {
                    true
                } else {
                    log.warn("Skipping AWS profile with disallowed characters: <redacted>")
                    false
                }
            }
            .toSortedSet()
        val merged = names.map { name ->
            val inCreds = credentials.containsKey(name)
            val inConfig = configProfiles.containsKey(name)
            val region = configProfiles[name]?.get("region")
                ?: credentials[name]?.get("region")
            AwsProfile(
                name = name,
                defaultRegion = region?.takeIf { it.isNotBlank() },
                source = when {
                    inCreds && inConfig -> AwsProfile.Source.BOTH
                    inCreds -> AwsProfile.Source.CREDENTIALS
                    else -> AwsProfile.Source.CONFIG
                },
            )
        }
        log.debug("Found {} AWS profile(s)", merged.size)
        return merged
    }

    private fun parseIni(file: File): Map<String, Map<String, String>> {
        if (!file.exists() || !file.canRead()) {
            log.debug("AWS config file not found or unreadable: {}", file.path)
            return emptyMap()
        }
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        try {
            file.forEachLine { rawLine ->
                val line = rawLine.substringBefore('#').substringBefore(';').trim()
                if (line.isEmpty()) return@forEachLine
                if (line.startsWith("[") && line.endsWith("]")) {
                    val sectionName = line.substring(1, line.length - 1).trim()
                    current = sections.getOrPut(sectionName) { mutableMapOf() }
                } else {
                    val eq = line.indexOf('=')
                    if (eq <= 0) return@forEachLine
                    val key = line.substring(0, eq).trim()
                    val value = line.substring(eq + 1).trim()
                    current?.put(key, value)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to parse AWS config file {}: {}", file.path, e.message)
        }
        return sections
    }
}
