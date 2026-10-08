package com.kubekubedashdash.yamledit

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.KubeConnectionManager
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.utils.Serialization
import org.slf4j.LoggerFactory

/** A fetched object: the full JSON tree and its resourceVersion. */
data class LiveObject(val json: LinkedHashMap<String, Any?>, val resourceVersion: String)

/** The result of a dry run. [simulatedLocally] = demo cluster, nothing was sent (D5); [result] is then null. */
data class DryRunOutcome(val result: LinkedHashMap<String, Any?>?, val simulatedLocally: Boolean)

/**
 * The write engine behind the YAML editor and the Apply window: reads a live object, replaces it
 * with a PUT that carries the base resourceVersion (D2) and applies manifests with server-side
 * apply (D12).
 *
 * Every method blocks (callers run it on IO) and throws only [YamlWriteException]: an HTTP failure
 * maps by status code ([toYamlWriteException]), any other failure becomes
 * `Request failed (<SimpleClassName>)` so no foreign message (a Jackson parse error quotes its
 * input) travels with it. Shared steps, in this order:
 * 1. the client of [context], or [WriteErrorKind.WrongCluster] when the manager is no longer
 *    connected to it;
 * 2. for a write, [MaskTokenGuard.check] of the exact string that goes on the wire, before any
 *    request ([WriteErrorKind.MaskedValue]);
 * 3. the request.
 *
 * The demo cluster's mock persists a `dryRun=All` request and has no server-side apply, so
 * [demoMode] (evaluated after step 1, with the same [context]) turns the dry-run methods into
 * local ones and gives [apply] a GET + POST / merge-PUT fallback. Nothing here ever sends a JSON,
 * strategic-merge or merge patch.
 *
 * Logs carry only kind, name, namespace, HTTP code and the error kind: never YAML, a payload, a
 * response body or an API error message, and never an exception object (its message would print).
 */
class YamlWriter(
    private val connectionManager: KubeConnectionManager,
    /**
     * True when [context] is a demo cluster: no dry run is ever sent (D5). Bound to the editor's
     * context string, not only to the manager's current state, so an editor opened on a demo tab
     * stays on the local path even while the manager is switching clusters.
     */
    private val demoMode: (context: String) -> Boolean = { ctx -> DemoContext.isMockContext(ctx) || connectionManager.isDemo },
) {
    private val log = LoggerFactory.getLogger(YamlWriter::class.java)

    /** True when [context] is a demo cluster, where dry runs are simulated locally (D5). */
    fun isDemo(context: String): Boolean = demoMode(context)

    /**
     * GETs [target] and returns its JSON tree and resourceVersion. Uses the three-argument `raw`:
     * the one-argument overload swallows a 404 and returns null, which would read as an empty answer.
     */
    fun fetch(target: EditTarget, context: String): LiveObject = mapped {
        val client = clientFor(context)
        val text = try {
            client.raw(target.path(), "GET", null)
        } catch (e: KubernetesClientException) {
            if (e.code == HTTP_NOT_FOUND) {
                throw YamlWriteException(WriteErrorKind.NotFound, HTTP_NOT_FOUND, "${target.kind} ${target.ref} was not found on the cluster.")
            }
            throw e
        }
        liveObject(text)
    }

    /**
     * Asks the server whether replacing [target] with [body] would be accepted (`PUT ?dryRun=All`).
     * On the demo cluster nothing is sent and the outcome is [DryRunOutcome.simulatedLocally].
     */
    fun dryRunReplace(target: EditTarget, context: String, body: Map<String, Any?>): DryRunOutcome = mapped {
        val client = clientFor(context)
        val json = wireJson(body)
        if (demoMode(context)) return@mapped DryRunOutcome(null, simulatedLocally = true)
        val text = client.raw(target.path() + "?fieldManager=$FIELD_MANAGER&dryRun=All", "PUT", json)
        DryRunOutcome(parseObject(text), simulatedLocally = false)
    }

    /**
     * Replaces [target] with [body] (`PUT`). [body] must carry the base `metadata.resourceVersion`:
     * a PUT without one would overwrite unconditionally, so it is refused as a caller error.
     */
    fun replace(target: EditTarget, context: String, body: Map<String, Any?>): LiveObject {
        val resourceVersion = (body["metadata"] as? Map<*, *>)?.get("resourceVersion")?.toString()
        require(!resourceVersion.isNullOrBlank()) { "A replace needs the base resourceVersion." }
        return mapped {
            val client = clientFor(context)
            val json = wireJson(body)
            logged(target, applying = false) {
                liveObject(client.raw(target.path() + "?fieldManager=$FIELD_MANAGER", "PUT", json))
            }
        }
    }

    // ── Slice B: apply a manifest ──────────────────────────────────────────────

    /**
     * What [apply] would do to [doc]: a server-side-apply dry run on a real cluster, a GET and a local
     * merge on the demo cluster (never a request that could be persisted there).
     */
    fun applyDryRun(doc: ApplyDocument, context: String): ApplyOutcome = mapped {
        val client = clientFor(context)
        val json = wireJson(doc.body)
        if (demoMode(context)) {
            val current = currentOf(client, doc.target) ?: return@mapped ApplyOutcome.Created
            return@mapped if (sameTree(mergedWith(current, doc), withoutStatus(current))) ApplyOutcome.Unchanged else ApplyOutcome.Configured
        }
        val resource = guardedResource(json)
        val current = currentOf(client, doc.target)
        outcomeOf(current, serverSideApply(client, doc.target, resource, dryRun = true))
    }

    /**
     * Applies [doc]: server-side apply (field manager [FIELD_MANAGER], no force) on a real cluster.
     * On the demo cluster: POST when the object is absent, else the document merged into the
     * current object and PUT with the current resourceVersion (nothing is sent when the merge changes
     * nothing).
     */
    fun apply(doc: ApplyDocument, context: String): ApplyOutcome = mapped {
        val client = clientFor(context)
        val json = wireJson(doc.body)
        val resource = if (demoMode(context)) null else guardedResource(json)
        logged(doc.target, applying = true) {
            val current = currentOf(client, doc.target)
            if (resource == null) {
                applyOnDemo(client, doc, json, current)
            } else {
                outcomeOf(current, serverSideApply(client, doc.target, resource, dryRun = false))
            }
        }
    }

    /**
     * The REST shape of [kind] in [apiVersion] from the cluster's discovery document, or null when
     * the cluster does not serve it (404), refuses discovery (403) or [apiVersion] is not a plain
     * `group/version`. Sub-resources (`pods/log`) are never matched.
     */
    fun discover(apiVersion: String, kind: String, context: String): ResolvedKind? = mapped {
        val client = clientFor(context)
        if (!API_VERSION.matches(apiVersion)) return@mapped null
        val list = try {
            client.getApiResources(apiVersion)
        } catch (e: KubernetesClientException) {
            if (e.code == HTTP_NOT_FOUND || e.code == HTTP_FORBIDDEN) return@mapped null
            throw e
        }
        val resource = list?.resources?.firstOrNull { it.kind == kind && '/' !in it.name } ?: return@mapped null
        val group = if ('/' in apiVersion) apiVersion.substringBefore('/') else ""
        ResolvedKind(group, apiVersion.substringAfter('/'), kind, resource.name, resource.namespaced == true)
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun clientFor(context: String): KubernetesClient = connectionManager.clientIfConnectedTo(context)
        ?: throw YamlWriteException(WriteErrorKind.WrongCluster, 0, "This editor belongs to $context, which its tab no longer shows.")

    /** Step 2 for a body: its JSON, which is the string that goes on the wire, checked for mask tokens. */
    private fun wireJson(body: Map<String, Any?>): String = Serialization.asJson(body).also { MaskTokenGuard.check(it) }

    /**
     * The object fabric8 serialises for a server-side apply (`asJson(item)`), built from the already
     * guarded [json]. Its own JSON is what goes on the wire, so that is checked too, and the same
     * instance must be the one passed to `.resource(...)`.
     */
    private fun guardedResource(json: String): GenericKubernetesResource {
        val resource = Serialization.unmarshal(json, GenericKubernetesResource::class.java)
        MaskTokenGuard.check(Serialization.asJson(resource))
        return resource
    }

    /** GET of the current object with the one-argument `raw`, which answers null for a 404 (= absent). */
    private fun currentOf(client: KubernetesClient, target: EditTarget): LinkedHashMap<String, Any?>? = client.raw(target.path())?.let { parseObject(it) }

    private fun serverSideApply(client: KubernetesClient, target: EditTarget, resource: GenericKubernetesResource, dryRun: Boolean): GenericKubernetesResource {
        val definition = ResourceDefinitionContext.Builder()
            .withGroup(target.group)
            .withVersion(target.version)
            .withKind(target.kind)
            .withPlural(target.plural)
            .withNamespaced(target.namespaced)
            .build()
        val patch = PatchContext.Builder().withPatchType(PatchType.SERVER_SIDE_APPLY).withFieldManager(FIELD_MANAGER)
        if (dryRun) patch.withDryRun(listOf("All"))
        val operation = client.genericKubernetesResources(definition)
        val scoped: Resource<GenericKubernetesResource> =
            if (target.namespace != null) operation.inNamespace(target.namespace).resource(resource) else operation.resource(resource)
        return scoped.patch(patch.build())
    }

    /** Created when nothing was there, Unchanged when [result] equals [current] apart from server bookkeeping, else Configured. */
    private fun outcomeOf(current: LinkedHashMap<String, Any?>?, result: GenericKubernetesResource): ApplyOutcome = when {
        current == null -> ApplyOutcome.Created
        normalize(current) == normalize(result) -> ApplyOutcome.Unchanged
        else -> ApplyOutcome.Configured
    }

    /**
     * The demo cluster's apply: its mock has no server-side apply, so the object is created with a
     * POST, or the document is deep-merged into the current object and PUT with the current
     * resourceVersion. The PUT is never sent without that resourceVersion, and its JSON is guarded
     * like every other wire string.
     */
    private fun applyOnDemo(client: KubernetesClient, doc: ApplyDocument, json: String, current: LinkedHashMap<String, Any?>?): ApplyOutcome {
        if (current == null) {
            client.raw(doc.target.collectionPath() + "?fieldManager=$FIELD_MANAGER", "POST", json)
            return ApplyOutcome.Created
        }
        val merged = mergedWith(current, doc)
        if (sameTree(merged, withoutStatus(current))) return ApplyOutcome.Unchanged
        val resourceVersion = (current["metadata"] as? Map<*, *>)?.get("resourceVersion")?.toString()?.takeIf { it.isNotBlank() }
            ?: throw YamlWriteException(WriteErrorKind.Other, 0, "The server returned no resourceVersion.")
        val body = LinkedHashMap(merged)
        current["status"]?.let { body["status"] = it }
        @Suppress("UNCHECKED_CAST")
        val metadata = body["metadata"] as? MutableMap<String, Any?>
        metadata?.put("resourceVersion", resourceVersion)
        client.raw(doc.target.path() + "?fieldManager=$FIELD_MANAGER", "PUT", wireJson(body))
        return ApplyOutcome.Configured
    }

    /** [doc]'s body merged over [current] (without its status and managedFields); the demo's stand-in for a server-side apply. */
    private fun mergedWith(current: Map<String, Any?>, doc: ApplyDocument): LinkedHashMap<String, Any?> = deepMerge(withoutStatus(current), doc.body)

    /** [current] without top-level `status` and `metadata.managedFields` (the fields the editor never shows). */
    private fun withoutStatus(current: Map<String, Any?>): LinkedHashMap<String, Any?> = EditProjection.visible(current)

    /** Both trees through JSON, so a number parsed as Int on one side and Long on the other still compares equal. */
    private fun sameTree(a: Map<String, Any?>, b: Map<String, Any?>): Boolean = jsonTree(a) == jsonTree(b)

    private fun jsonTree(value: Any): LinkedHashMap<String, Any?> = parseObject(Serialization.asJson(value))

    /** [value] as a Jackson tree minus the fields that differ between two reads of the same object. */
    private fun normalize(value: Any): LinkedHashMap<String, Any?> {
        val tree = jsonTree(value)

        @Suppress("UNCHECKED_CAST")
        val metadata = tree["metadata"] as? MutableMap<String, Any?>
        if (metadata != null) {
            for (field in listOf("managedFields", "resourceVersion", "generation")) metadata.remove(field)
        }
        return tree
    }

    private fun liveObject(text: String?): LiveObject {
        val json = parseObject(text)
        val resourceVersion = (json["metadata"] as? Map<*, *>)?.get("resourceVersion")?.toString()?.takeIf { it.isNotBlank() }
            ?: throw YamlWriteException(WriteErrorKind.Other, 0, "The server returned no resourceVersion.")
        return LiveObject(json, resourceVersion)
    }

    /** A JSON object as a Jackson tree; anything else is [WriteErrorKind.Other] without the parser's message (it quotes the input). */
    @Suppress("UNCHECKED_CAST")
    private fun parseObject(text: String?): LinkedHashMap<String, Any?> {
        if (text == null) throw YamlWriteException(WriteErrorKind.Other, 0, "The server returned an empty answer.")
        val parsed = try {
            Serialization.unmarshal(text, LinkedHashMap::class.java)
        } catch (e: Exception) {
            throw YamlWriteException(WriteErrorKind.Other, 0, "Request failed (${(e.cause ?: e).javaClass.simpleName})")
        }
        return parsed as? LinkedHashMap<String, Any?> ?: throw YamlWriteException(WriteErrorKind.Other, 0, "The server returned no object.")
    }

    /** Logs the start of a write and, when it fails, its code and kind; [request] failures are mapped first. */
    private fun <T> logged(target: EditTarget, applying: Boolean, request: () -> T): T {
        if (applying) {
            log.info("Applying {} {} ns={}", target.kind, target.name, target.namespace)
        } else {
            log.info("Replacing {} {} ns={}", target.kind, target.name, target.namespace)
        }
        try {
            return mapped(request)
        } catch (e: YamlWriteException) {
            if (applying) {
                log.warn("Apply failed {} {} ns={} code={} kind={}", target.kind, target.name, target.namespace, e.code, e.kind.name)
            } else {
                log.warn("Replace failed {} {} ns={} code={} kind={}", target.kind, target.name, target.namespace, e.code, e.kind.name)
            }
            throw e
        }
    }

    /** Runs [block], turning every failure into a [YamlWriteException] (see the class comment). */
    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: YamlWriteException) {
        throw e
    } catch (e: KubernetesClientException) {
        throw e.toYamlWriteException()
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        throw YamlWriteException(WriteErrorKind.Other, 0, "Request failed (${e.javaClass.simpleName})")
    }

    companion object {
        /** The field manager every write carries, so `managedFields` names this app. */
        const val FIELD_MANAGER = "kubekubedashdash"

        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_FORBIDDEN = 403

        /** `v1` or `group/version`: what discovery is asked for, kept a plain path. */
        private val API_VERSION = Regex("""[A-Za-z0-9.\-]+(/[A-Za-z0-9.\-]+)?""")
    }
}
