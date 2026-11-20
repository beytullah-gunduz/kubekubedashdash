package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.informers.cache.BasicItemStore
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.ItemStore
import java.util.stream.Stream

/**
 * Informer item store for the Secrets list: keeps what the list row reads — metadata, `type` and
 * the `data` key names — and drops every value, `stringData`, `managedFields` and the
 * last-applied annotation (which re-embeds the whole manifest). The secret payload therefore never
 * sits in the informer cache; the YAML tab fetches the object on demand.
 */
internal class SecretSummaryStore : ItemStore<Secret> {
    private val delegate = BasicItemStore<Secret> { Cache.metaNamespaceKeyFunc(it) }

    override fun getKey(obj: Secret): String = delegate.getKey(obj)
    override fun put(key: String, obj: Secret): Secret? = delegate.put(key, summarize(obj))
    override fun remove(key: String): Secret? = delegate.remove(key)
    override fun keySet(): Stream<String> = delegate.keySet()
    override fun values(): Stream<Secret> = delegate.values()
    override fun size(): Int = delegate.size()
    override fun get(key: String): Secret? = delegate.get(key)

    companion object {
        const val LAST_APPLIED_ANNOTATION = "kubectl.kubernetes.io/last-applied-configuration"

        fun summarize(s: Secret): Secret {
            val meta = s.metadata?.let { m ->
                ObjectMetaBuilder(m)
                    .withManagedFields(null as List<io.fabric8.kubernetes.api.model.ManagedFieldsEntry>?)
                    .withAnnotations<String, String>(m.annotations?.filterKeys { it != LAST_APPLIED_ANNOTATION })
                    .build()
            }
            return SecretBuilder()
                .withMetadata(meta)
                .withType(s.type)
                .withImmutable(s.immutable)
                .withData<String, String>(s.data?.mapValues { "" })
                .build()
        }
    }
}
