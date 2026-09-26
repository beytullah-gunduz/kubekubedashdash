package com.kubekubedashdash.releasecheck;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import com.kubekubedashdash.logging.AppLogEntry;
import com.kubekubedashdash.logging.AppLogStore;
import com.kubekubedashdash.logging.InMemoryAppender;
import com.kubekubedashdash.mcp.McpServerManager;
import com.kubekubedashdash.util.CrdJsonPath;
import com.kubekubedashdash.util.MockClusterHandle;
import com.kubekubedashdash.util.MockClusterProvider;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

/**
 * Headless smoke test of the ProGuard-shrunk release jars, run by the
 * {@code releaseCanary} and {@code releaseCanaryLimitedModules} Gradle tasks with those
 * jars FIRST on the classpath. Each check drives a code path that a shrinker can break
 * while every unit test (which runs unshrunk) stays green: logging configuration loaded
 * by class name, reflective JSONPath and enum lookups, the demo cluster's mock server
 * (signed BouncyCastle jars), and the MCP server's Ktor/SSE stack (where a dropped
 * direct interface made the first coroutine Job fail verification).
 *
 * <p>Prints PASS/FAIL per check and exits with the number of failures. Never prints log
 * contents. Refuses to start unless every state location points into a scratch sandbox,
 * so it can never read or write the developer's kubeconfig, preferences or logs.
 */
public final class ReleaseCanary {
    private static final long WATCHDOG_MINUTES = 3;
    private static final String LOGGER = "com.kubekubedashdash.releasecheck";

    private static int failures;
    private static volatile String running = "startup";

    private ReleaseCanary() {
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void check(String name, Check body) {
        running = name;
        try {
            body.run();
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failures++;
            // A leading line break: a broken log pattern can leave the console mid-line.
            System.out.println(System.lineSeparator() + "FAIL " + name + ": " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        requireSandbox();
        startWatchdog();

        check("slf4j-bound-to-logback", () -> {
            String factory = LoggerFactory.getILoggerFactory().getClass().getName();
            require(factory.equals("ch.qos.logback.classic.LoggerContext"), "ILoggerFactory is " + factory);
        });

        check("in-memory-appender", () -> {
            InMemoryAppender appender = InMemoryAppender.Companion.getInstance();
            require(appender != null, "logback.xml created no InMemoryAppender");
            require(appender.isStarted(), "InMemoryAppender is not started");
            String marker = "canary-in-memory-" + System.nanoTime();
            LoggerFactory.getLogger(LOGGER).info(marker);
            boolean received = false;
            for (AppLogEntry entry : AppLogStore.INSTANCE.getEntries().getValue()) {
                received |= entry.getMessage().contains(marker);
            }
            require(received, "the log drawer's store did not receive the canary entry");
        });

        check("crd-jsonpath", () -> {
            String json = "{\"apiVersion\":\"example.com/v1\",\"kind\":\"Widget\","
                + "\"metadata\":{\"name\":\"w1\",\"namespace\":\"default\"},"
                + "\"spec\":{\"replicas\":3},"
                + "\"status\":{\"phase\":\"Running\",\"conditions\":["
                + "{\"type\":\"Ready\",\"status\":\"True\"},{\"type\":\"Synced\",\"status\":\"False\"}]}}";
            GenericKubernetesResource resource = Serialization.unmarshal(json, GenericKubernetesResource.class);
            Instant now = Instant.now();
            CrdJsonPath path = CrdJsonPath.INSTANCE;
            String phase = path.evaluate(resource, ".status.phase", "string", now);
            require(phase.equals("Running"), "phase=" + phase);
            String ready = path.evaluate(resource, ".status.conditions[?(@.type==\"Ready\")].status", "string", now);
            require(ready.equals("True"), "ready=" + ready);
            String replicas = path.evaluate(resource, ".spec.replicas", "integer", now);
            require(replicas.equals("3"), "replicas=" + replicas);
            String missing = path.evaluate(resource, ".status.nope", "string", now);
            require(missing.equals("<none>"), "missing=" + missing);
        });

        check("jediterm", () -> {
            ClassLoader loader = ReleaseCanary.class.getClassLoader();
            for (String name : new String[] {
                "com.jediterm.terminal.ui.JediTermWidget", "com.jediterm.terminal.TtyConnector",
                "com.jediterm.terminal.ui.settings.DefaultSettingsProvider",
                "com.kubekubedashdash.terminal.KubectlExecTtyConnector",
            }) {
                // Reflection links the class, which runs the bytecode verifier on it; loading
                // alone would not. None of these can be initialised headless.
                Class.forName(name, false, loader).getDeclaredMethods();
            }
            // Initialised, not just loaded: TextStyle.<clinit> reads TextStyle$Option's enum
            // constants reflectively, which fails if the shrinker strips values()/$VALUES.
            Class.forName("com.jediterm.terminal.TextStyle", true, loader);
        });

        check("demo-cluster", () -> {
            MockClusterHandle handle = MockClusterProvider.INSTANCE.acquire("canary");
            try {
                int pods = handle.getClient().pods().inAnyNamespace().list().getItems().size();
                require(pods > 0, "the demo cluster listed " + pods + " pods");
            } finally {
                handle.close();
            }
        });

        check("mcp-sse-session", ReleaseCanary::mcpSession);

        check("logback-file-and-fold", () -> {
            String home = System.getProperty("user.home");
            // Written through at once (immediateFlush), so app.log can be read while open. The
            // context is deliberately never stopped: a log event from a lingering thread after
            // stop() makes logback report a "No appenders" WARN, failing logback-status.
            LoggerFactory.getLogger(LOGGER).info("canary-fold " + home + "/x");
            Path log = Path.of(System.getProperty("LOG_DIR"), "app.log");
            require(Files.exists(log), "app.log was not written");
            String text = Files.readString(log);
            require(!text.contains("PARSER_ERROR"), "app.log contains PARSER_ERROR (a pattern converter failed to load)");
            require(text.contains("canary-fold ~/x"), "the folded canary line is missing from app.log");
            require(!text.contains(home), "app.log contains the unfolded home path");
        });

        // Last, so it also sees anything the checks above made logback report.
        check("logback-status", () -> {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            List<String> problems = context.getStatusManager().getCopyOfStatusList().stream()
                .filter(status -> status.getEffectiveLevel() >= Status.WARN)
                .map(Object::toString)
                .toList();
            require(problems.isEmpty(), "logback reported " + problems);
        });

        System.out.println(failures == 0 ? "CANARY OK" : "CANARY FAILED (" + failures + " checks)");
        System.exit(failures);
    }

    /** Refuses to run unless the Gradle task's scratch sandbox is in place. */
    private static void requireSandbox() {
        String root = System.getProperty("kkdd.canary.sandbox");
        boolean ok = root != null && !root.isBlank();
        if (ok) {
            Path sandbox = Path.of(root).toAbsolutePath().normalize();
            ok = isKubeconfigInSandbox(System.getenv("KUBECONFIG"), sandbox);
            // The app reads the `kubeconfig` property before $KUBECONFIG; unset is fine.
            String kubeconfigProperty = System.getProperty("kubeconfig");
            ok &= kubeconfigProperty == null || isKubeconfigInSandbox(kubeconfigProperty, sandbox);
            for (String location : new String[] {
                System.getProperty("user.home"), System.getenv("HOME"), System.getProperty("java.io.tmpdir"),
                System.getProperty("LOG_DIR"), System.getProperty("kkdd.dataDir"),
            }) {
                ok &= location != null && isInSandbox(location, sandbox);
            }
        }
        if (!ok) {
            System.out.println("FAIL sandbox: run this through the releaseCanary Gradle task, which points "
                + "KUBECONFIG, HOME, user.home, java.io.tmpdir, LOG_DIR and kkdd.dataDir into a scratch sandbox");
            System.exit(1);
        }
    }

    /** One existing file inside the sandbox: the app splits a kubeconfig spec on the path separator. */
    private static boolean isKubeconfigInSandbox(String spec, Path sandbox) {
        return spec != null && !spec.contains(File.pathSeparator) && isInSandbox(spec, sandbox) && Files.isRegularFile(Path.of(spec));
    }

    private static boolean isInSandbox(String location, Path sandbox) {
        return Path.of(location).toAbsolutePath().normalize().startsWith(sandbox);
    }

    /** A hung check must fail the build, not stall it. */
    private static void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(WATCHDOG_MINUTES));
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("FAIL " + running + ": still running after " + WATCHDOG_MINUTES + " minutes");
            Runtime.getRuntime().halt(1);
        }, "canary-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void mcpSession() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        McpServerManager manager = McpServerManager.INSTANCE;
        // Fresh preferences: localhost-only with a generated bearer token.
        manager.start(port);
        HttpURLConnection sse = null;
        try {
            require(manager.isRunning(), "the MCP server did not start");
            String token = manager.getBearerToken();
            require(token != null, "the MCP server generated no bearer token");
            URI base = URI.create("http://127.0.0.1:" + port + "/");

            // HttpURLConnection, not java.net.http: the canary must need no module the
            // app's jlink runtime could legitimately drop.
            HttpURLConnection anonymous = open(base, null);
            anonymous.setRequestProperty("Accept", "text/event-stream");
            int anonymousStatus = anonymous.getResponseCode();
            anonymous.disconnect();
            require(anonymousStatus == 401, "a request without the token got " + anonymousStatus);

            sse = open(base, token);
            sse.setRequestProperty("Accept", "text/event-stream");
            sse.setReadTimeout(0);
            require(sse.getResponseCode() == 200, "the SSE stream answered " + sse.getResponseCode());
            BufferedReader stream = new BufferedReader(new InputStreamReader(sse.getInputStream(), StandardCharsets.UTF_8));
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = new Thread(() -> {
                try {
                    for (String line = stream.readLine(); line != null; line = stream.readLine()) lines.add(line);
                } catch (IOException ignored) {
                    // The stream ends when the server stops.
                }
            }, "canary-sse");
            reader.setDaemon(true);
            reader.start();

            String endpoint = awaitData(lines, "sessionId=").substring("data:".length()).trim();
            URI messages = base.resolve(endpoint);
            post(messages, token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"release-canary\",\"version\":\"0\"}}}");
            require(awaitData(lines, "\"id\":1").contains("serverInfo"), "the initialize result has no serverInfo");
            post(messages, token, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            post(messages, token, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            String tools = awaitData(lines, "\"id\":2");
            for (String tool : new String[] {"get_resource_yaml", "get_pod_logs", "list_resources", "list_clusters"}) {
                require(tools.contains("\"" + tool + "\""), "tools/list lacks " + tool);
            }
            post(messages, token, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"list_clusters\",\"arguments\":{}}}");
            String clusters = awaitData(lines, "\"id\":3");
            // The tool's JSON payload travels as an escaped string inside the JSON-RPC result.
            require(clusters.contains("\\\"clusters\\\":[") && !clusters.contains("\"isError\":true"),
                "list_clusters returned no cluster list");
        } finally {
            manager.stop();
            if (sse != null) sse.disconnect();
        }
        require(!manager.isRunning(), "the MCP server is still running after stop()");
    }

    private static HttpURLConnection open(URI uri, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    /** The first SSE {@code data:} line containing [marker]; each line may take up to 10 s. */
    private static String awaitData(BlockingQueue<String> lines, String marker) throws InterruptedException {
        for (String line = lines.poll(10, TimeUnit.SECONDS); line != null; line = lines.poll(10, TimeUnit.SECONDS)) {
            if (line.startsWith("data:") && line.contains(marker)) return line;
        }
        throw new AssertionError("no SSE data line containing " + marker);
    }

    private static void post(URI uri, String token, String body) throws IOException {
        HttpURLConnection connection = open(uri, token);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        try (InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            if (in != null) in.readAllBytes();
        }
        require(status / 100 == 2, "POST answered " + status);
    }
}
