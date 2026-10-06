package com.kubekubedashdash.util

/**
 * Values read out of text pasted into a discovery modal's "Enter by name" tab. A null field
 * was not present in the text. Nothing here is validated: the caller copies these values into
 * the form fields, and the form's own checks — the same ones that guard argv — decide whether
 * Import is allowed. The pasted text is never executed.
 */
data class GkeClusterReference(val projectId: String?, val location: String?, val clusterName: String?)

data class EksClusterReference(val profile: String?, val region: String?, val clusterName: String?)

object ClusterReferenceParser {

    // projects/P/locations/L/clusters/C, or the legacy zones/ form, anywhere in the text: an API
    // URL, a resource name, or the path quoted in a gcloud error message.
    private val GKE_RESOURCE_RX = Regex("""projects/([^/\s"']+)/(?:locations|zones)/([^/\s"']+)/clusters/([^/\s"'?#]+)""")

    // A cluster ARN in any partition (aws, aws-cn, aws-us-gov, aws-iso-b, …).
    private val EKS_ARN_RX = Regex("""arn:aws(?:-[a-z]+)*:eks:([a-z0-9-]+):\d+:cluster/([A-Za-z0-9_-]+)""")

    private val GKE_LOCATION_FLAGS = setOf("--location", "--region", "--zone", "-z")

    // gcloud flags that take a value as the next word. Anything else starting with "-" is read
    // as a boolean flag, so the next word stays a candidate for the cluster name.
    private val GCLOUD_VALUE_FLAGS = GKE_LOCATION_FLAGS + setOf(
        "--project",
        "--account",
        "--access-token-file",
        "--billing-project",
        "--configuration",
        "--filter",
        "--flags-file",
        "--flatten",
        "--format",
        "--impersonate-service-account",
        "--verbosity",
        "--trace-token",
    )

    private val AWS_VALUE_FLAGS = setOf(
        "--name",
        "--region",
        "--profile",
        "--kubeconfig",
        "--role-arn",
        "--alias",
        "--user-alias",
        "--output",
        "--endpoint-url",
        "--query",
        "--color",
        "--ca-bundle",
        "--cli-read-timeout",
        "--cli-connect-timeout",
    )

    fun parseGke(text: String): GkeClusterReference? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.none { it.isWhitespace() }) {
            GkeClusterDiscoverer.parseGkeContext(trimmed)?.let { (project, location, cluster) ->
                return GkeClusterReference(project, location, cluster)
            }
        }
        gkeCommand(tokenize(trimmed))?.let { return it }
        GKE_RESOURCE_RX.find(trimmed)?.let { m ->
            return GkeClusterReference(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        return null
    }

    fun parseEks(text: String): EksClusterReference? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        eksCommand(tokenize(trimmed))?.let { return it }
        EKS_ARN_RX.find(trimmed)?.let { m ->
            return EksClusterReference(profile = null, region = m.groupValues[1], clusterName = m.groupValues[2])
        }
        return null
    }

    private fun gkeCommand(tokens: List<String>): GkeClusterReference? {
        val start = (2 until tokens.size).firstOrNull { i ->
            tokens[i] == "get-credentials" && tokens[i - 1] == "clusters" && tokens[i - 2] == "container"
        } ?: return null
        var project: String? = null
        var location: String? = null
        var cluster: String? = null
        var i = start + 1
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.startsWith("-")) {
                val name = token.substringBefore('=')
                var value = if ('=' in token) stripQuotes(token.substringAfter('=')) else null
                if (value == null && name in GCLOUD_VALUE_FLAGS) {
                    val next = tokens.getOrNull(i + 1)
                    if (next != null && !next.startsWith("-")) {
                        value = next
                        i++
                    }
                }
                if (!value.isNullOrEmpty()) {
                    when (name) {
                        "--project" -> project = value
                        in GKE_LOCATION_FLAGS -> location = value
                    }
                }
            } else if (cluster == null) {
                cluster = token
            }
            i++
        }
        if (project == null && location == null && cluster == null) return null
        return GkeClusterReference(project, location, cluster)
    }

    private fun eksCommand(tokens: List<String>): EksClusterReference? {
        val start = (1 until tokens.size).firstOrNull { i ->
            tokens[i] == "update-kubeconfig" && tokens[i - 1] == "eks"
        } ?: return null
        var profile: String? = null
        var region: String? = null
        var cluster: String? = null
        // Environment assignments before the command: AWS_PROFILE=x AWS_REGION=y aws eks …
        for (token in tokens.subList(0, start)) {
            when {
                token.startsWith("AWS_PROFILE=") -> profile = token.substringAfter('=').ifEmpty { null }

                token.startsWith("AWS_REGION=") || token.startsWith("AWS_DEFAULT_REGION=") ->
                    region = token.substringAfter('=').ifEmpty { null }
            }
        }
        var i = start + 1
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.startsWith("--")) {
                val name = token.substringBefore('=')
                var value = if ('=' in token) stripQuotes(token.substringAfter('=')) else null
                if (value == null && name in AWS_VALUE_FLAGS) {
                    val next = tokens.getOrNull(i + 1)
                    if (next != null && !next.startsWith("--")) {
                        value = next
                        i++
                    }
                }
                if (!value.isNullOrEmpty()) {
                    when (name) {
                        "--name" -> cluster = value
                        "--region" -> region = value
                        "--profile" -> profile = value
                    }
                }
            }
            i++
        }
        if (profile == null && region == null && cluster == null) return null
        return EksClusterReference(profile, region, cluster)
    }

    /**
     * Splits pasted shell text into words. Whitespace separates; a backslash line continuation,
     * a lone `\` and a lone `$` prompt are dropped; one pair of matching surrounding quotes is
     * stripped from each word. Quoted whitespace is not supported — none of the values read here
     * can contain a space.
     */
    internal fun tokenize(text: String): List<String> = text
        .replace("\\\r\n", " ")
        .replace("\\\n", " ")
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() && it != "\\" && it != "$" }
        .map { stripQuotes(it) }
        .filter { it.isNotEmpty() }

    internal fun stripQuotes(word: String): String {
        val quoted = word.length >= 2 && (word.first() == '"' || word.first() == '\'') && word.last() == word.first()
        return if (quoted) word.substring(1, word.length - 1) else word
    }
}
