package com.kubekubedashdash.util

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.PathNotFoundException
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.client.utils.Serialization
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Evaluate `additionalPrinterColumns` JSONPath expressions against a
 * [GenericKubernetesResource] for display in tables. Wraps Jayway JsonPath
 * with the surface that CRD authors actually use:
 *
 * - K8s `additionalPrinterColumns.jsonPath` always starts with `.` whereas
 *   Jayway expects `$.`. We translate.
 * - Missing paths must render as a placeholder, never throw, since CRD
 *   instances frequently lack `status` until reconciled.
 * - `type: date` values (strings or numeric epoch seconds) are rendered as
 *   relative ages ("3m", "2d") to match what `kubectl get` prints.
 * - `type: integer` / `number` are rendered as concise strings (no trailing
 *   `.0` on whole numbers).
 */
object CrdJsonPath {

    private val log = LoggerFactory.getLogger(CrdJsonPath::class.java)

    private val config: Configuration = Configuration.defaultConfiguration()
        .addOptions(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)

    private const val MISSING = "<none>"

    fun evaluate(
        resource: GenericKubernetesResource,
        path: String,
        type: String,
        now: Instant = Instant.now(),
    ): String {
        val raw = readRaw(resource, path) ?: return MISSING
        return if (raw is List<*>) {
            raw.joinToString(", ") { el -> if (el == null) "" else format(el, type, now) }
        } else {
            format(raw, type, now)
        }
    }

    private fun readRaw(resource: GenericKubernetesResource, path: String): Any? {
        val normalized = normalizePath(path)
        val json = try {
            Serialization.asJson(resource)
        } catch (e: Exception) {
            log.warn("Failed to serialize CR {} for JSONPath eval: {}", resource.metadata?.name, e.message)
            return null
        }
        return try {
            val v = JsonPath.using(config).parse(json).read<Any?>(normalized)
            unwrapList(v)
        } catch (_: PathNotFoundException) {
            null
        } catch (e: Exception) {
            log.debug("JSONPath eval failed for path {}: {}", normalized, e.message)
            null
        }
    }

    /**
     * Kubernetes printer-column paths are written `.foo.bar` or `.foo[0].bar`
     * (no leading dollar). Jayway requires `$.foo.bar`. Filter expressions
     * (`[?(@.type=="Ready")]`) work unchanged once the leading `.` becomes
     * `$.`.
     */
    private fun normalizePath(path: String): String = when {
        path.startsWith("$") -> path
        path.startsWith(".") -> "$$path"
        else -> "$.$path"
    }

    /**
     * Filtered paths like `.status.conditions[?(@.type=="Ready")].status`
     * yield a JsonArray with a single element (or many). Unwrap to the first
     * value when there's exactly one match — that's the "kubectl-equivalent"
     * rendering. Multi-match arrays are joined as `a, b, c`.
     */
    private fun unwrapList(value: Any?): Any? = when (value) {
        is List<*> -> when {
            value.isEmpty() -> null
            value.size == 1 -> value[0]
            else -> value
        }

        else -> value
    }

    private fun format(raw: Any, type: String, now: Instant): String = when (type.lowercase()) {
        "date" -> formatDate(raw, now)

        "integer" -> when (raw) {
            is Number -> raw.toLong().toString()
            else -> raw.toString()
        }

        "number" -> when (raw) {
            is Number -> {
                val d = raw.toDouble()
                if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
            }

            else -> raw.toString()
        }

        "boolean" -> raw.toString()

        else -> raw.toString()
    }

    private fun formatDate(raw: Any, now: Instant): String = try {
        val instant = when (raw) {
            is Number -> Instant.ofEpochSecond(raw.toLong())
            is String -> Instant.parse(raw)
            else -> return raw.toString()
        }
        formatDuration(Duration.between(instant, now))
    } catch (_: DateTimeParseException) {
        raw.toString()
    } catch (_: Exception) {
        raw.toString()
    }

    private fun formatDuration(d: Duration): String {
        val s = d.seconds
        return when {
            s < 0 -> "0s"
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            else -> "${s / 86_400}d"
        }
    }
}
