package com.kubekubedashdash.helm

import com.kubekubedashdash.models.ResourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HelmSourcesTest {

    private val forbiddenMessage =
        "Failure executing: GET at: https://example-cluster.test/api/v1/secrets. Message: secrets is forbidden: " +
            "User \"dev@example.com\" cannot list resource \"secrets\". " +
            "Received status: Status(apiVersion=v1, code=403, kind=Status, reason=Forbidden, status=Failure)."

    private val timeoutMessage = "Failure executing: GET at: https://example-cluster.test/api/v1/secrets. Message: timed out."

    private fun ref(name: String, driver: HelmDriver = HelmDriver.SECRET) = HelmRevisionRef(
        driver = driver,
        namespace = "default",
        objectName = "sh.helm.release.v1.$name.v1",
        uid = "uid-$name",
        resourceVersion = "1",
        releaseName = name,
        revision = 1,
        status = "deployed",
        createdAtEpochSeconds = null,
        modifiedAtEpochSeconds = null,
        creationTimestamp = null,
        epoch = 1,
    )

    private val secretRefs = listOf(ref("web"), ref("api"))
    private val configMapRefs = listOf(ref("cfg", HelmDriver.CONFIGMAP))

    private fun success(refs: List<HelmRevisionRef>): ResourceState<List<HelmRevisionRef>> = ResourceState.Success(refs)

    private val loading: ResourceState<List<HelmRevisionRef>> = ResourceState.Loading

    private fun error(message: String): ResourceState<List<HelmRevisionRef>> = ResourceState.Error(message)

    @Test
    fun `secrets loading is loading whatever the configmaps say`() {
        for (configMaps in listOf(loading, success(configMapRefs), error(forbiddenMessage))) {
            assertEquals(HelmSourceState.Loading, combineHelmSources(loading, configMaps))
        }
    }

    @Test
    fun `both lists loaded are combined with no warning`() {
        val state = assertIs<HelmSourceState.Ready>(combineHelmSources(success(secretRefs), success(configMapRefs)))

        assertEquals(secretRefs + configMapRefs, state.refs)
        assertEquals(null, state.warning)
    }

    @Test
    fun `secrets loaded while configmaps load is ready with the secrets alone`() {
        val state = assertIs<HelmSourceState.Ready>(combineHelmSources(success(secretRefs), loading))

        assertEquals(secretRefs, state.refs)
        assertEquals(null, state.warning)
    }

    @Test
    fun `a forbidden configmap list warns and keeps the secrets`() {
        val state = assertIs<HelmSourceState.Ready>(combineHelmSources(success(secretRefs), error(forbiddenMessage)))

        assertEquals(secretRefs, state.refs)
        assertEquals(HELM_CONFIGMAP_FORBIDDEN_WARNING, state.warning)
        assertFalse("dev@example.com" in state.warning!!)
    }

    @Test
    fun `another configmap failure warns generically and keeps the secrets`() {
        val state = assertIs<HelmSourceState.Ready>(combineHelmSources(success(secretRefs), error(timeoutMessage)))

        assertEquals(secretRefs, state.refs)
        assertEquals("Releases stored in ConfigMaps are not shown: the ConfigMap list failed.", state.warning)
    }

    @Test
    fun `a failed secret list with configmap releases shows them with a warning`() {
        val forbidden = assertIs<HelmSourceState.Ready>(combineHelmSources(error(forbiddenMessage), success(configMapRefs)))
        val other = assertIs<HelmSourceState.Ready>(combineHelmSources(error(timeoutMessage), success(configMapRefs)))

        assertEquals(configMapRefs, forbidden.refs)
        assertEquals(HELM_FORBIDDEN_MESSAGE, forbidden.warning)
        assertEquals(configMapRefs, other.refs)
        assertEquals("Releases stored in Secrets are not shown: the Secret list failed.", other.warning)
    }

    @Test
    fun `a failed secret list with nothing else to show is a failure`() {
        for (configMaps in listOf(success(emptyList()), loading, error(forbiddenMessage), error(timeoutMessage))) {
            val forbidden = assertIs<HelmSourceState.Failed>(combineHelmSources(error(forbiddenMessage), configMaps))
            assertEquals(HELM_FORBIDDEN_MESSAGE, forbidden.message)
            assertTrue(forbidden.forbidden)
            assertFalse("dev@example.com" in forbidden.message, "the 403 text names the user and is replaced")

            val other = assertIs<HelmSourceState.Failed>(combineHelmSources(error(timeoutMessage), configMaps))
            assertEquals(timeoutMessage, other.message, "a non-403 failure keeps fabric8's text")
            assertFalse(other.forbidden)
        }
    }
}
