package com.kubekubedashdash.util

import com.kubekubedashdash.services.LogStreamOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * Made-up pod logs for the built-in demo cluster. The mock API server can't hold a follow stream
 * open (its responses are buffered, then EOF), so demo connections read logs from here instead.
 * Output is seeded by namespace/pod/container: the same pod always tells the same story.
 */
internal object DemoPodLogs {
    /** [count] historical lines, oldest first, ending at [now]. */
    fun history(
        namespace: String,
        pod: String,
        container: String?,
        count: Int,
        timestamps: Boolean,
        now: Instant = Instant.now(),
    ): List<String> = historyEntries(namespace, pod, container, count, now).map { render(it, timestamps) }

    /**
     * Live stream: [tailLines] history lines, then — while collected — one new line every
     * [minGapMs]..[maxGapMs] ms. Never completes on its own (like `kubectl logs -f`).
     */
    fun stream(
        namespace: String,
        pod: String,
        container: String?,
        options: LogStreamOptions,
        tailLines: Int = 100,
        minGapMs: Long = 400,
        maxGapMs: Long = 2_500,
        clock: () -> Instant = Instant::now,
    ): Flow<String> = flow {
        if (options.previous) {
            // A previous container is never followed: it produced all its output already.
            terminated(namespace, pod, container, tailLines, options.timestamps, clock()).forEach { emit(it) }
            return@flow
        }
        val start = clock()
        val oldest = options.sinceSeconds?.let { start.minusSeconds(it.toLong()) }
        historyEntries(namespace, pod, container, tailLines, start)
            .filter { oldest == null || !it.at.isBefore(oldest) }
            .forEach { emit(render(it, options.timestamps)) }

        val seed = seedOf(namespace, pod, container)
        val rnd = Random(seed + 1)
        val gen = LineGen(familyOf(pod), pod.substringBefore('-').lowercase(Locale.ROOT), rnd)
        while (true) {
            delay(rnd.nextLong(minGapMs, maxGapMs + 1))
            val at = clock()
            emit(render(Entry(at, gen.next(at)), options.timestamps))
        }
    }

    /** One-shot read for a terminated pod or a previous container: history ending in a crash. */
    fun terminated(
        namespace: String,
        pod: String,
        container: String?,
        count: Int,
        timestamps: Boolean,
        now: Instant = Instant.now(),
    ): List<String> {
        val family = familyOf(pod)
        val app = pod.substringBefore('-').lowercase(Locale.ROOT)
        val step = stepMsOf(seedOf(namespace, pod, container))
        val entries = historyEntries(namespace, pod, container, (count - FINAL_LINES).coerceAtLeast(0), now.minusMillis(2 * step)) +
            finalEntries(family, app, now, step)
        return entries.takeLast(count.coerceAtLeast(0)).map { render(it, timestamps) }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private const val FINAL_LINES = 3

    private class Entry(val at: Instant, val body: String)

    private enum class Family { NGINX, API, REDIS, KAFKA, SPARK, GENERIC }

    private val KUBECTL_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.nnnnnnnnn'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val NGINX_ACCESS_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val NGINX_ERROR_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val REDIS_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val KAFKA_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val SPARK_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)

    private fun seedOf(namespace: String, pod: String, container: String?): Int = "$namespace/$pod/${container.orEmpty()}".hashCode()

    /** Gap between two history lines; drawn first from the seeded random so every caller agrees on it. */
    private fun stepMsOf(seed: Int): Long = Random(seed).nextLong(800, 4_001)

    private fun familyOf(pod: String): Family {
        val app = pod.substringBefore('-').lowercase(Locale.ROOT)
        return when {
            app == "frontend" || app == "nginx" || app == "web" || app == "ingress" -> Family.NGINX
            app == "api" || app == "backend" -> Family.API
            app == "redis" -> Family.REDIS
            app == "kafka" -> Family.KAFKA
            app.startsWith("spark") -> Family.SPARK
            else -> Family.GENERIC
        }
    }

    private fun render(entry: Entry, timestamps: Boolean): String = if (timestamps) KUBECTL_TIMESTAMP.format(entry.at) + " " + entry.body else entry.body

    /** Line `i` (0-based, oldest first) is at `now - (count - i) * step`. */
    private fun historyEntries(namespace: String, pod: String, container: String?, count: Int, now: Instant): List<Entry> {
        if (count <= 0) return emptyList()
        val seed = seedOf(namespace, pod, container)
        val rnd = Random(seed)
        val step = rnd.nextLong(800, 4_001)
        val gen = LineGen(familyOf(pod), pod.substringBefore('-').lowercase(Locale.ROOT), rnd)
        return List(count) { i ->
            val at = now.minusMillis((count - i) * step)
            Entry(at, gen.next(at))
        }
    }

    /** The last [FINAL_LINES] lines of a pod that died: a fatal error, a shutdown, an exit status. */
    private fun finalEntries(family: Family, app: String, now: Instant, step: Long): List<Entry> {
        val t1 = now.minusMillis(2 * step)
        val t2 = now.minusMillis(step)
        return when (family) {
            Family.API -> listOf(
                Entry(t1, """{"ts":"${ISO_MILLIS.format(t1)}","level":"error","msg":"fatal","error":"java.lang.OutOfMemoryError: Java heap space"}"""),
                Entry(t2, """{"ts":"${ISO_MILLIS.format(t2)}","level":"info","msg":"shutting down"}"""),
                Entry(now, "exit status 137"),
            )

            Family.KAFKA -> listOf(
                Entry(t1, "[${KAFKA_TIME.format(t1)}] ERROR Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space (kafka.Kafka\$)"),
                Entry(t2, "[${KAFKA_TIME.format(t2)}] INFO [KafkaServer id=0] shutting down (kafka.server.KafkaServer)"),
                Entry(now, "exit status 137"),
            )

            Family.SPARK -> listOf(
                Entry(t1, "${SPARK_TIME.format(t1)} ERROR Executor: Exception in task 0.0 in stage 0.0: java.lang.OutOfMemoryError: Java heap space"),
                Entry(t2, "${SPARK_TIME.format(t2)} INFO CoarseGrainedExecutorBackend: shutting down"),
                Entry(now, "exit status 137"),
            )

            Family.NGINX -> listOf(
                Entry(t1, "${NGINX_ERROR_TIME.format(t1)} [emerg] 1#1: bind() to 0.0.0.0:8080 failed (98: Address already in use)"),
                Entry(t2, "${NGINX_ERROR_TIME.format(t2)} [notice] 1#1: shutting down"),
                Entry(now, "exit status 1"),
            )

            Family.REDIS -> listOf(
                Entry(t1, "1:M ${REDIS_TIME.format(t1)} # [emerg] bind() to 0.0.0.0:8080 failed (98: Address already in use)"),
                Entry(t2, "1:M ${REDIS_TIME.format(t2)} # Redis is shutting down"),
                Entry(now, "exit status 1"),
            )

            Family.GENERIC -> listOf(
                Entry(t1, "${ISO_MILLIS.format(t1)} ERROR [$app-1] Main - [emerg] bind() to 0.0.0.0:8080 failed (98: Address already in use)"),
                Entry(t2, "${ISO_MILLIS.format(t2)} INFO [$app-1] Main - shutting down"),
                Entry(now, "exit status 1"),
            )
        }
    }

    /**
     * Stateful line source for one pod: counters (offsets, TIDs, generations) advance from line to
     * line so the story reads as a continuous log rather than independent random draws.
     */
    private class LineGen(private val family: Family, private val app: String, private val rnd: Random) {
        private var tid = rnd.nextInt(1_000, 90_000)
        private var task = rnd.nextInt(0, 200)
        private var stage = rnd.nextInt(1, 30)
        private var offset = rnd.nextLong(100_000, 9_000_000)
        private var generation = rnd.nextInt(10, 400)
        private var batchId = rnd.nextInt(1_000, 90_000)
        private var redisPhase = 0

        fun next(at: Instant): String = when (family) {
            Family.NGINX -> nginx(at)
            Family.API -> api(at)
            Family.REDIS -> redis(at)
            Family.KAFKA -> kafka(at)
            Family.SPARK -> spark(at)
            Family.GENERIC -> generic(at)
        }

        private fun podIp(): String = "10.244.${rnd.nextInt(0, 4)}.${rnd.nextInt(2, 255)}"

        private fun hex16(): String = "%016x".format(Locale.ROOT, rnd.nextLong())

        private fun nginx(at: Instant): String {
            val path = when (rnd.nextInt(100)) {
                in 0 until 30 -> "/"
                in 30 until 50 -> "/api/products"
                in 50 until 62 -> "/api/cart"
                in 62 until 78 -> "/static/app.js"
                in 78 until 88 -> "/healthz"
                else -> "/api/orders/${rnd.nextInt(1_000, 10_000)}"
            }
            val method = if (path == "/api/cart" && rnd.nextInt(100) < 35) "POST" else "GET"
            val roll = rnd.nextInt(100)
            val status = when {
                roll < 86 -> 200
                roll < 92 -> 304
                roll < 97 -> 404
                else -> 500
            }
            val bytes = when (status) {
                200 -> if (path == "/healthz") 2 else rnd.nextInt(512, 48_000)
                304 -> 0
                404 -> 153
                else -> 177
            }
            val ua = if (path == "/healthz") {
                "kube-probe/1.30"
            } else {
                when (rnd.nextInt(10)) {
                    in 0 until 8 -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
                    8 -> "kube-probe/1.30"
                    else -> "curl/8.7.1"
                }
            }
            val rt = "0.%03d".format(Locale.ROOT, rnd.nextInt(1, 400))
            return "${podIp()} - - [${NGINX_ACCESS_TIME.format(at)}] \"$method $path HTTP/1.1\" $status $bytes \"-\" \"$ua\" rt=$rt"
        }

        private fun api(at: Instant): String {
            val ts = ISO_MILLIS.format(at)
            val path = when (rnd.nextInt(5)) {
                0 -> "/v1/orders"
                1 -> "/v1/orders/${rnd.nextInt(1_000, 10_000)}"
                2 -> "/v1/products"
                3 -> "/v1/cart"
                else -> "/v1/users/me"
            }
            val method = if (path == "/v1/cart" && rnd.nextInt(100) < 40) "POST" else "GET"
            val roll = rnd.nextInt(100)
            return when {
                roll < 90 ->
                    """{"ts":"$ts","level":"info","msg":"request completed","method":"$method","path":"$path","status":200,"duration_ms":${rnd.nextInt(2, 90)},"trace_id":"${hex16()}"}"""

                roll < 97 ->
                    """{"ts":"$ts","level":"warn","msg":"slow query","method":"$method","path":"$path","status":200,"duration_ms":${rnd.nextInt(800, 900)},"trace_id":"${hex16()}"}"""

                else ->
                    """{"ts":"$ts","level":"error","msg":"upstream timeout","upstream":"redis:6379","method":"$method","path":"$path","status":504,"duration_ms":3000,"trace_id":"${hex16()}"}"""
            }
        }

        private fun redis(at: Instant): String {
            val msg = when (redisPhase) {
                1 -> {
                    redisPhase = 2
                    "Background saving started by pid ${rnd.nextInt(20, 9_000)}"
                }

                2 -> {
                    redisPhase = 0
                    "DB saved on disk"
                }

                else -> if (rnd.nextInt(100) < 80) {
                    "Accepted ${podIp()}:${rnd.nextInt(32_768, 61_000)}"
                } else {
                    redisPhase = 1
                    "${rnd.nextInt(10, 400)} changes in 60 seconds. Saving..."
                }
            }
            return "1:M ${REDIS_TIME.format(at)} * $msg"
        }

        private fun kafka(at: Instant): String {
            val ts = KAFKA_TIME.format(at)
            return if (rnd.nextInt(100) < 70) {
                offset += rnd.nextLong(500, 5_000)
                "[$ts] INFO [Log partition=orders-${rnd.nextInt(0, 12)}, dir=/var/lib/kafka] Rolled new log segment at offset $offset (kafka.log.LocalLog)"
            } else {
                generation++
                "[$ts] INFO [GroupCoordinator 0]: Stabilized group billing generation $generation (kafka.coordinator.group.GroupCoordinator)"
            }
        }

        private fun spark(at: Instant): String {
            val thisTask = task
            task = (task + 1) % 200
            if (task == 0) stage++
            val thisTid = tid++
            return "${SPARK_TIME.format(at)} INFO TaskSetManager: Finished task $thisTask.0 in stage $stage.0 (TID $thisTid) " +
                "in ${rnd.nextInt(40, 900)} ms on ${podIp()} (executor ${rnd.nextInt(1, 5)}) (${thisTask + 1}/200)"
        }

        private fun generic(at: Instant): String {
            val ts = ISO_MILLIS.format(at)
            val thread = "$app-${rnd.nextInt(1, 9)}"
            val roll = rnd.nextInt(100)
            return when {
                roll < 88 -> if (rnd.nextInt(100) < 65) {
                    batchId++
                    "$ts INFO [$thread] BatchProcessor - processed batch id=$batchId items=${rnd.nextInt(8, 500)} in ${rnd.nextInt(5, 400)}ms"
                } else {
                    "$ts INFO [$thread] HeartbeatMonitor - heartbeat ok lag=${rnd.nextInt(1, 60)}ms"
                }

                roll < 97 -> "$ts WARN [$thread] JobRunner - retrying job ${rnd.nextInt(1_000, 90_000)} (attempt 2/5)"

                else -> "$ts ERROR [$thread] LockManager - failed to acquire lock jobs/${rnd.nextInt(1_000, 90_000)}: timeout"
            }
        }
    }
}
