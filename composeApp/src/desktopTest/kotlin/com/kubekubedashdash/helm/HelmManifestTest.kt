package com.kubekubedashdash.helm

import com.kubekubedashdash.util.SecretYamlMasking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelmManifestTest {

    private companion object {
        const val SECRET_VALUE = "c2VjcmV0LW1hcmtlci12YWx1ZQ=="
        const val SECRET_TEXT = "marker-plain-secret-text"
        const val NOTE = HelmManifest.HIDDEN_DOCUMENT_NOTE
        const val PLACEHOLDER = SecretYamlMasking.PLACEHOLDER
    }

    private fun manifest(vararg docs: String): String = docs.joinToString("") { "---\n# Source: demo/templates/x.yaml\n$it\n" }

    private fun assertMasked(output: String, vararg secrets: String) {
        for (secret in secrets) assertFalse(secret in output, "\"$secret\" leaked:\n$output")
    }

    private fun assertHidden(output: String, vararg secrets: String) {
        assertTrue(NOTE in output, "the document is hidden:\n$output")
        assertMasked(output, *secrets)
    }

    // ── split / join ────────────────────────────────────────────────────────

    @Test
    fun `join of split gives back the input`() {
        val inputs = listOf(
            "",
            "just: text",
            "---\na: 1\n---\nb: 2\n",
            "a: 1\n---\nb: 2",
            "a: 1\n",
            "--- # comment\na: 1\n--- \nb: 2\n---\n",
            "a: 1\r\n---\r\nb: 2\r\n",
            "\n\n---\n\n",
        )
        for (input in inputs) assertEquals(input, HelmManifest.join(HelmManifest.split(input)), "input: ${input.replace("\n", "\\n")}")
    }

    @Test
    fun `split puts each separator at the head of its document`() {
        val docs = HelmManifest.split("---\n# Source: a\nx: 1\n--- # note\ny: 2\n")

        assertEquals(3, docs.size)
        assertNull(docs[0].separator)
        assertTrue(docs[0].lines.isEmpty())
        assertEquals("---", docs[1].separator)
        assertEquals(listOf("# Source: a", "x: 1"), docs[1].lines)
        assertEquals("--- # note", docs[2].separator)
    }

    @Test
    fun `three dashes glued to text are not a separator`() {
        val docs = HelmManifest.split("a: 1\n---x\n----\nb: 2")

        assertEquals(1, docs.size)
    }

    // ── mask: Secrets ───────────────────────────────────────────────────────

    @Test
    fun `a Secret's data and stringData are masked and its metadata is kept`() {
        val secret = """
            apiVersion: v1
            kind: Secret
            metadata:
              name: demo-secret
              namespace: default
            type: Opaque
            data:
              password: $SECRET_VALUE
            stringData:
              token: $SECRET_TEXT
        """.trimIndent()

        val output = HelmManifest.mask(manifest(secret))

        assertMasked(output, SECRET_VALUE, SECRET_TEXT)
        assertFalse(NOTE in output, "masked, not hidden")
        assertTrue("name: demo-secret" in output)
        assertTrue("password: $PLACEHOLDER" in output)
        assertTrue("token: $PLACEHOLDER" in output)
        assertTrue("# Source: demo/templates/x.yaml" in output)
    }

    @Test
    fun `a ConfigMap is unchanged`() {
        val configMap = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm\ndata:\n  key: plain-value"
        val input = manifest(configMap)

        assertEquals(input, HelmManifest.mask(input))
    }

    @Test
    fun `a quoted data key is masked like a bare one`() {
        val secret = "apiVersion: v1\nkind: Secret\nmetadata:\n  name: q\n\"data\":\n  password: $SECRET_VALUE\n'stringData':\n  token: $SECRET_TEXT"

        val output = HelmManifest.mask(manifest(secret))

        assertMasked(output, SECRET_VALUE, SECRET_TEXT)
        assertFalse(NOTE in output, "masked, not hidden")
        assertTrue("password: $PLACEHOLDER" in output)
    }

    @Test
    fun `a one-line flow-style Secret is hidden because masking cannot reach it`() {
        val output = HelmManifest.mask(manifest("{apiVersion: v1, kind: Secret, data: {k: $SECRET_VALUE}}"))

        assertHidden(output, SECRET_VALUE)
        assertTrue("# Source: demo/templates/x.yaml" in output, "the source comment survives")
    }

    @Test
    fun `a JSON-style Secret is hidden`() {
        val oneLine = """{"apiVersion":"v1","kind":"Secret","data":{"k":"$SECRET_VALUE"}}"""
        val multiLine = "{\n  \"apiVersion\": \"v1\",\n  \"kind\": \"Secret\",\n  \"data\": {\n    \"k\": \"$SECRET_VALUE\"\n  }\n}"

        assertHidden(HelmManifest.mask(manifest(oneLine)), SECRET_VALUE)
        assertHidden(HelmManifest.mask(manifest(multiLine)), SECRET_VALUE)
    }

    @Test
    fun `a List holding a Secret is hidden and a List without one is unchanged`() {
        val withSecret = "apiVersion: v1\nkind: List\nitems:\n  - apiVersion: v1\n    kind: Secret\n    data:\n      k: $SECRET_VALUE"
        val withoutSecret = "apiVersion: v1\nkind: List\nitems:\n  - apiVersion: v1\n    kind: ConfigMap\n    data:\n      k: plain"
        val nested = "apiVersion: v1\nkind: List\nitems:\n  - kind: List\n    items:\n      - kind: Secret\n        data:\n          k: $SECRET_VALUE"

        assertHidden(HelmManifest.mask(manifest(withSecret)), SECRET_VALUE)
        assertHidden(HelmManifest.mask(manifest(nested)), SECRET_VALUE)
        val plain = manifest(withoutSecret)
        assertEquals(plain, HelmManifest.mask(plain))
    }

    @Test
    fun `a document without a kind is hidden`() {
        assertHidden(HelmManifest.mask(manifest("data:\n  k: $SECRET_VALUE")), SECRET_VALUE)
        assertHidden(HelmManifest.mask(manifest("kind: 5\ndata:\n  k: $SECRET_VALUE")), SECRET_VALUE)
    }

    @Test
    fun `a document that is not a mapping is hidden`() {
        assertHidden(HelmManifest.mask(manifest("- $SECRET_VALUE\n- other")), SECRET_VALUE)
        assertHidden(HelmManifest.mask(manifest(SECRET_VALUE)), SECRET_VALUE)
    }

    @Test
    fun `unparsable YAML is hidden`() {
        assertHidden(HelmManifest.mask(manifest("kind: Secret\ndata: [unclosed\n  k: $SECRET_VALUE")), SECRET_VALUE)
    }

    @Test
    fun `the Source comment of a hidden document is kept and the rest of its lines are not`() {
        val input = "---\n# Source: demo/templates/odd.yaml\n# another comment\n{kind: Secret, data: {k: $SECRET_VALUE}}\n"

        val output = HelmManifest.mask(input)

        assertTrue("# Source: demo/templates/odd.yaml" in output)
        assertFalse("another comment" in output)
        assertHidden(output, SECRET_VALUE)
        assertTrue(output.startsWith("---\n"), "the separator is kept")
    }

    @Test
    fun `a separator that carries content hides the document after it`() {
        val input = "--- {kind: Secret}\nkind: ConfigMap\ndata:\n  k: $SECRET_TEXT\n"

        assertHidden(HelmManifest.mask(input), SECRET_TEXT)
    }

    @Test
    fun `a separator with only a comment does not hide a ConfigMap`() {
        val input = "--- # note\nkind: ConfigMap\ndata:\n  k: plain\n"

        assertEquals(input, HelmManifest.mask(input))
    }

    @Test
    fun `a literal block scalar under data leaves no secret text`() {
        val secret = "apiVersion: v1\nkind: Secret\ndata: |\n  $SECRET_VALUE\n  second-$SECRET_VALUE"

        val output = HelmManifest.mask(manifest(secret))

        assertMasked(output, SECRET_VALUE)
    }

    @Test
    fun `a folded block scalar under data leaves no secret text`() {
        val secret = "apiVersion: v1\nkind: Secret\ndata: >\n  $SECRET_VALUE\n  second-$SECRET_VALUE"

        assertMasked(HelmManifest.mask(manifest(secret)), SECRET_VALUE)
    }

    @Test
    fun `a multi-line flow mapping under data leaves no secret text`() {
        val secret = "apiVersion: v1\nkind: Secret\ndata: {\n  k: $SECRET_VALUE,\n  j: $SECRET_VALUE\n}"

        assertMasked(HelmManifest.mask(manifest(secret)), SECRET_VALUE)
    }

    @Test
    fun `a CRLF manifest with a Secret is masked`() {
        val secret = "apiVersion: v1\r\nkind: Secret\r\nmetadata:\r\n  name: crlf\r\ndata:\r\n  k: $SECRET_VALUE\r\nstringData:\r\n  t: $SECRET_TEXT\r\n"
        val input = "---\r\n# Source: demo/templates/crlf.yaml\r\n$secret"

        val output = HelmManifest.mask(input)

        assertMasked(output, SECRET_VALUE, SECRET_TEXT)
    }

    @Test
    fun `a non-string leaf under stringData is masked`() {
        val secret = "apiVersion: v1\nkind: Secret\nstringData:\n  port: 5432\n  flag: true"

        val output = HelmManifest.mask(manifest(secret))

        assertMasked(output, "5432", "true")
        assertTrue("port: $PLACEHOLDER" in output)
    }

    @Test
    fun `a non-string leaf the masker cannot see makes the verification hide the document`() {
        // A complex key (`? stringData` / `: ...`) is not a `key: value` line, so the masker leaves it.
        // The parsed value is a number and the Secret is hidden instead.
        val numeric = "apiVersion: v1\nkind: Secret\n? stringData\n: {port: 5432}"
        val text = "apiVersion: v1\nkind: Secret\n? data\n: {k: $SECRET_VALUE}"

        assertHidden(HelmManifest.mask(manifest(numeric)), "5432")
        assertHidden(HelmManifest.mask(manifest(text)), SECRET_VALUE)
    }

    @Test
    fun `a Secret with an extra top-level key is hidden`() {
        val secret = "apiVersion: v1\nkind: Secret\nstatus:\n  phase: odd\ndata:\n  k: $SECRET_VALUE"

        assertHidden(HelmManifest.mask(manifest(secret)), SECRET_VALUE)
    }

    @Test
    fun `a merge key or an alias in a Secret is hidden`() {
        val merge = "apiVersion: v1\nkind: Secret\nx: &shared\n  k: $SECRET_VALUE\n<<: *shared"
        val alias = "apiVersion: v1\nkind: Secret\ndata: &d\n  k: $SECRET_VALUE\nstringData: *d"

        assertHidden(HelmManifest.mask(manifest(merge)), SECRET_VALUE)
        assertMasked(HelmManifest.mask(manifest(alias)), SECRET_VALUE)
    }

    @Test
    fun `the last-applied-configuration annotation is masked`() {
        val secret = "apiVersion: v1\nkind: Secret\nmetadata:\n  name: annotated\n  annotations:\n" +
            "    kubectl.kubernetes.io/last-applied-configuration: '{\"data\":{\"k\":\"$SECRET_VALUE\"}}'\n" +
            "data:\n  k: $SECRET_VALUE"

        val output = HelmManifest.mask(manifest(secret))

        assertMasked(output, SECRET_VALUE)
        assertTrue("name: annotated" in output)
    }

    @Test
    fun `a comment-only document and an empty one are unchanged`() {
        val input = "---\n# Source: demo/templates/empty.yaml\n# nothing rendered\n---\n\n---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: after\n"

        assertEquals(input, HelmManifest.mask(input))
    }

    @Test
    fun `a Secret kind is matched case-insensitively and a custom resource named like one is not a Secret`() {
        val lower = "apiVersion: v1\nkind: secret\ndata:\n  k: $SECRET_VALUE"
        val sealed = "apiVersion: bitnami.com/v1alpha1\nkind: SealedSecret\nspec:\n  encryptedData:\n    k: AgBy3i4OJSWK"

        assertMasked(HelmManifest.mask(manifest(lower)), SECRET_VALUE)
        val plain = manifest(sealed)
        assertEquals(plain, HelmManifest.mask(plain))
    }

    // ── resources ───────────────────────────────────────────────────────────

    @Test
    fun `resources lists kinds, names and namespaces sorted by kind then name`() {
        val input = manifest(
            "apiVersion: v1\nkind: Service\nmetadata:\n  name: b-svc\n  namespace: other",
            "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: web",
            "apiVersion: v1\nkind: Service\nmetadata:\n  name: a-svc",
        )

        val resources = HelmManifest.resources(input, "release-ns")

        assertEquals(
            listOf(
                ManifestResource("apps/v1", "Deployment", "web", "release-ns"),
                ManifestResource("v1", "Service", "a-svc", "release-ns"),
                ManifestResource("v1", "Service", "b-svc", "other"),
            ),
            resources,
        )
    }

    @Test
    fun `a cluster-scoped kind has no namespace unless the document names one`() {
        val input = manifest(
            "apiVersion: rbac.authorization.k8s.io/v1\nkind: ClusterRole\nmetadata:\n  name: reader",
            "apiVersion: v1\nkind: Namespace\nmetadata:\n  name: extra",
        )

        val resources = HelmManifest.resources(input, "release-ns")

        assertEquals(listOf(null, null), resources.map { it.namespace })
        assertEquals(listOf("ClusterRole", "Namespace"), resources.map { it.kind })
    }

    @Test
    fun `a List contributes its items`() {
        val list = "apiVersion: v1\nkind: List\nitems:\n  - apiVersion: v1\n    kind: ConfigMap\n    metadata:\n      name: one\n" +
            "  - apiVersion: v1\n    kind: ConfigMap\n    metadata:\n      name: two\n      namespace: elsewhere"

        val resources = HelmManifest.resources(manifest(list), "ns")

        assertEquals(listOf("one" to "ns", "two" to "elsewhere"), resources.map { it.name to it.namespace })
    }

    @Test
    fun `documents that are not objects with a kind and a name are skipped`() {
        val input = manifest(
            "# only a comment",
            "- not\n- a mapping",
            "kind: ConfigMap",
            "apiVersion: v1\nmetadata:\n  name: no-kind",
            "kind: [unclosed",
            "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: ok",
        )

        val resources = HelmManifest.resources(input, "ns")

        assertEquals(listOf("ok"), resources.map { it.name })
    }

    @Test
    fun `the API group is empty for the core group and the part before the slash otherwise`() {
        assertEquals("", ManifestResource("v1", "Pod", "p", null).group)
        assertEquals("apps", ManifestResource("apps/v1", "Deployment", "d", null).group)
        assertEquals("networking.k8s.io", ManifestResource("networking.k8s.io/v1", "Ingress", "i", null).group)
        assertEquals("", ManifestResource("", "Pod", "p", null).group)
    }
}
