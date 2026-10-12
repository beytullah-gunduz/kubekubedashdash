package com.kubekubedashdash.util

import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.models.SparkRun
import com.kubekubedashdash.models.WorkloadRun
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The run data the usage history charts read: a Job's times and a SparkApplication's pods and submission. */
class ResourceMappersRunDataTest {

    // ── Jobs ────────────────────────────────────────────────────────────────────

    private fun job(
        completionMode: String? = null,
        start: String? = null,
        completion: String? = null,
        conditions: List<Triple<String, String, String>> = emptyList(),
    ): Job = JobBuilder()
        .withNewMetadata().withName("nightly-backup-29123456").withNamespace("default").endMetadata()
        .withNewSpec().withCompletionMode(completionMode).endSpec()
        .withNewStatus()
        .withStartTime(start)
        .withCompletionTime(completion)
        .also { status -> conditions.forEach { (type, state, at) -> status.addNewCondition().withType(type).withStatus(state).withLastTransitionTime(at).endCondition() } }
        .endStatus()
        .build()

    @Test
    fun `a finished Job's end is its completion time`() {
        val run = ResourceMappers.mapJobRun(
            job(start = "2026-10-12T08:00:00Z", completion = "2026-10-12T08:05:30Z", conditions = listOf(Triple("Complete", "True", "2026-10-12T08:05:31Z"))),
        )

        assertEquals(WorkloadRun(startTime = "2026-10-12T08:00:00Z", endTime = "2026-10-12T08:05:30Z", indexed = false), run)
    }

    @Test
    fun `a failed Job has no completion time, so its end is when it turned Failed`() {
        val run = ResourceMappers.mapJobRun(
            job(
                start = "2026-10-12T08:00:00Z",
                conditions = listOf(
                    Triple("Suspended", "True", "2026-10-12T08:00:01Z"),
                    Triple("Failed", "False", "2026-10-12T08:02:00Z"),
                    Triple("Failed", "True", "2026-10-12T08:05:00Z"),
                    Triple("Complete", "True", "2026-10-12T08:06:00Z"),
                ),
            ),
        )

        assertEquals("2026-10-12T08:05:00Z", run.endTime, "the first Complete or Failed condition that is True")
    }

    @Test
    fun `a running Job has a start and no end`() {
        assertEquals(WorkloadRun("2026-10-12T08:00:00Z", "", false), ResourceMappers.mapJobRun(job(start = "2026-10-12T08:00:00Z")))
        assertEquals(
            WorkloadRun("2026-10-12T08:00:00Z", "", false),
            ResourceMappers.mapJobRun(job(start = "2026-10-12T08:00:00Z", conditions = listOf(Triple("Complete", "False", "2026-10-12T08:01:00Z")))),
        )
    }

    @Test
    fun `an Indexed Job is read from its completion mode`() {
        assertEquals(true, ResourceMappers.mapJobRun(job(completionMode = "Indexed")).indexed)
        assertEquals(false, ResourceMappers.mapJobRun(job(completionMode = "NonIndexed")).indexed)
        assertEquals(false, ResourceMappers.mapJobRun(job(completionMode = null)).indexed)
    }

    @Test
    fun `a Job without a spec or a status maps to no times`() {
        val bare = JobBuilder().withNewMetadata().withName("db-vacuum").endMetadata().build()

        assertEquals(WorkloadRun("", "", false), ResourceMappers.mapJobRun(bare))
    }

    // ── SparkApplications ───────────────────────────────────────────────────────

    private val sparkCrd = CrdInfo(
        group = "sparkoperator.k8s.io",
        version = "v1beta2",
        kind = "SparkApplication",
        plural = "sparkapplications",
        singular = "sparkapplication",
        shortNames = emptyList(),
        categories = emptyList(),
        scope = CrdScope.NAMESPACED,
        columns = emptyList(),
    )

    private fun resource(
        kind: String = "SparkApplication",
        group: String = "sparkoperator.k8s.io",
        spec: Any? = null,
        status: Any? = null,
        created: String? = "2026-10-12T07:59:00Z",
    ): GenericKubernetesResource {
        val r = GenericKubernetesResource()
        r.apiVersion = "$group/v1beta2"
        r.kind = kind
        r.metadata = ObjectMeta().apply {
            name = "spark-etl-nightly"
            namespace = "production"
            uid = "uid-spark-1"
            creationTimestamp = created
        }
        if (spec != null) r.additionalProperties["spec"] = spec
        if (status != null) r.additionalProperties["status"] = status
        return r
    }

    private val fullStatus = mapOf(
        "applicationState" to mapOf("state" to "COMPLETED"),
        "driverInfo" to mapOf("podName" to "spark-etl-nightly-driver"),
        "executorState" to mapOf(
            "spark-etl-nightly-1a2b3c4d5e6f7890-exec-2" to "COMPLETED",
            "spark-etl-nightly-1a2b3c4d5e6f7890-exec-1" to "COMPLETED",
        ),
        "lastSubmissionAttemptTime" to "2026-10-12T08:00:00Z",
        "terminationTime" to "2026-10-12T08:20:00Z",
        "executionAttempts" to 1,
    )

    private fun spark(r: GenericKubernetesResource, crd: CrdInfo = sparkCrd) = ResourceMappers.mapCrInstance(r, crd).spark

    @Test
    fun `a SparkApplication's status gives its driver, sorted executors, times and state`() {
        val info = ResourceMappers.mapCrInstance(resource(status = fullStatus), sparkCrd)

        assertEquals(
            SparkRun(
                driverPod = "spark-etl-nightly-driver",
                executorPods = listOf("spark-etl-nightly-1a2b3c4d5e6f7890-exec-1", "spark-etl-nightly-1a2b3c4d5e6f7890-exec-2"),
                submittedAt = "2026-10-12T08:00:00Z",
                terminatedAt = "2026-10-12T08:20:00Z",
                state = "COMPLETED",
            ),
            info.spark,
        )
        assertEquals("2026-10-12T07:59:00Z", info.creationTimestamp)
    }

    @Test
    fun `the driver pod is the status name, else the spec name, else the application name and -driver`() {
        val specDriver = mapOf("driver" to mapOf("podName" to "spark-etl-driver-custom"))

        assertEquals("spark-etl-nightly-driver", spark(resource(spec = specDriver, status = fullStatus))?.driverPod)
        assertEquals("spark-etl-driver-custom", spark(resource(spec = specDriver, status = fullStatus - "driverInfo"))?.driverPod)
        assertEquals("spark-etl-driver-custom", spark(resource(spec = specDriver))?.driverPod)
        assertEquals("spark-etl-nightly-driver", spark(resource(spec = mapOf("driver" to mapOf("cores" to 1)), status = mapOf("driverInfo" to mapOf("podName" to ""))))?.driverPod)
        assertEquals("spark-etl-nightly-driver", spark(resource())?.driverPod)
    }

    @Test
    fun `a SparkApplication with no status maps to empty values, not to nothing`() {
        assertEquals(SparkRun("spark-etl-nightly-driver", emptyList(), "", "", ""), spark(resource()))
        assertEquals(SparkRun("spark-etl-nightly-driver", emptyList(), "", "", ""), spark(resource(status = emptyMap<String, Any>(), spec = emptyMap<String, Any>())))
    }

    @Test
    fun `a field of the wrong type gives no Spark data and never throws`() {
        for (status in listOf<Any>(
            "oops",
            listOf("a"),
            42,
            mapOf("driverInfo" to "spark-etl-nightly-driver"),
            mapOf("driverInfo" to mapOf("podName" to 5)),
            mapOf("executorState" to listOf("exec-1")),
            mapOf("applicationState" to listOf("COMPLETED")),
            mapOf("lastSubmissionAttemptTime" to mapOf("at" to "2026-10-12T08:00:00Z")),
            mapOf("terminationTime" to 1760256000),
        )) {
            assertNull(spark(resource(status = status)), "status $status")
        }
        for (spec in listOf<Any>("oops", listOf(1), mapOf("driver" to "x"), mapOf("driver" to mapOf("podName" to 7)))) {
            assertNull(spark(resource(spec = spec, status = emptyMap<String, Any>())), "spec $spec")
        }
    }

    @Test
    fun `only a SparkApplication of the Spark operator carries Spark data and a creation time`() {
        val other = ResourceMappers.mapCrInstance(resource(status = fullStatus), sparkCrd.copy(group = "example.com"))
        val scheduled = ResourceMappers.mapCrInstance(resource(kind = "ScheduledSparkApplication", status = fullStatus), sparkCrd.copy(kind = "ScheduledSparkApplication"))
        val unrelated = ResourceMappers.mapCrInstance(resource(kind = "Widget", group = "example.com", status = fullStatus), sparkCrd.copy(group = "example.com", kind = "Widget"))

        for (info in listOf(other, scheduled, unrelated)) {
            assertNull(info.spark)
            assertEquals("", info.creationTimestamp)
        }
    }

    @Test
    fun `a SparkApplication without a creation time keeps an empty one`() {
        assertEquals("", ResourceMappers.mapCrInstance(resource(created = null), sparkCrd).creationTimestamp)
    }
}
