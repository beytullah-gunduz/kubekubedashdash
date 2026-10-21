# Contributing to KubeKubeDashDash

How to build the app from source, run it and its tests, and produce the installers. What the app does is in the [user guide](GUIDE.md).

## Building from source

- **JDK 17** or later to start the Gradle wrapper. The build itself runs on **Temurin 21**, pinned in `gradle/gradle-daemon-jvm.properties`: Gradle uses an installed Temurin 21, or downloads one on macOS and Windows. On Linux, install Temurin 21 yourself.
- The packaged installers bundle their own Java runtime; none of this is needed to use them.

Run the app:

```bash
./gradlew :composeApp:run
```

It reads your kubeconfig like the installed app does. Without a cluster, pick **Demo Cluster** in the cluster picker.

Code formatting is checked in CI (Spotless + ktlint); apply it locally with:

```bash
./gradlew spotlessApply
```

## Tests

The desktop test suite:

```bash
./gradlew :composeApp:desktopTest
```

### UI smoke test (macOS)

`./gradlew :composeApp:uiSmoke` launches fresh demo-only copies of the app and checks windows, tabs and
the window-wide history through the Compose Hot Reload MCP server. It opens real windows, so run it on
a desktop session and leave the mouse alone; it never reads your kubeconfig, preferences or cloud
accounts. What it covers and how it stays isolated: the KDoc of
`composeApp/src/desktopTest/kotlin/com/kubekubedashdash/uismoke/UiSmoke.kt`.

### Integration tests (K3s)

`./gradlew :composeApp:k3sIntegrationTest` starts a throwaway [K3s](https://k3s.io) cluster with
[Testcontainers](https://testcontainers.com), runs a plain Prometheus in it and checks the pod history
charts' in-cluster source against it, as an admin and as a user that holds only the built-in `view`
role. It needs a reachable Docker and a few minutes (the first run also pulls three images). It is not
part of `desktopTest` or CI, and it builds its clients from the cluster's own credentials, so your
kubeconfig is never read. With Docker unreachable it fails at once; it never skips.

`./gradlew :composeApp:k3sDevCluster` starts the same cluster, writes `composeApp/build/k3s/kubeconfig-admin.yaml`,
`kubeconfig-viewer.yaml` and `kubeconfig-viewer-ns.yaml` (the viewer's file with the workload namespace as its
context's `namespace:`; throwaway credentials, owner-readable), prints `K3S READY`, and keeps it up
until `composeApp/build/k3s/stop` exists (`touch composeApp/build/k3s/stop`) or for 60 minutes. Point a
hot run at one of them with `-PhotRunKubeconfig=build/k3s/kubeconfig-admin.yaml` (a relative path is
taken from `composeApp/`).

With [Colima](https://github.com/abiosoft/colima), point Testcontainers at Colima's socket (replace
`default` with your profile name):

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

If the cluster starts but its API port cannot be reached on `localhost`, start Colima with
`colima start --network-address` and also
`export TESTCONTAINERS_HOST_OVERRIDE="$(colima ls -j | jq -r '.address')"`. Gradle's configuration cache
keeps the environment of the run that stored it, so after changing these variables run the task once with
`--no-configuration-cache`.

Docker Desktop and a native Linux Docker usually need none of these. The K3s module does not work with
rootless Docker or Docker-in-Docker.

## Building distributable packages

```bash
# macOS
./gradlew :composeApp:packageDmg

# Windows
./gradlew :composeApp:packageMsi

# Linux
./gradlew :composeApp:packageDeb
```

The installers ship ProGuard-shrunk jars, which no unit test runs. `verifyReleaseBuild` checks them: a bytecode scan fails on any `invokespecial` of an interface method through an indirect superinterface (the JVM verifier rejects those at class load), and a headless canary boots the shrunk jars — logging, JSONPath, JediTerm, the YAML editor engine and RSyntaxTextArea, the demo cluster and a full MCP session — once on the full JDK and once limited to the packaged runtime's modules. It runs in a scratch sandbox and never reads your kubeconfig, preferences or logs:

```bash
./gradlew :composeApp:verifyReleaseBuild
```

## CI and releases

Every push and PR to `main` checks formatting, compiles, runs the desktop test suite and verifies the release build on Linux. Pushing a `v*` tag runs the same checks on macOS, Linux and Windows, builds the installers (DMG, DEB, MSI) and creates a GitHub Release with them. For a stable tag it then points the Homebrew cask at the new DMG (see [packaging/homebrew](packaging/homebrew/README.md)).

## Screenshots and the website

The website is `docs/index.html`, served by GitHub Pages from `main:/docs`. Its images, and the ones in the README and the user guide, come from the live app against the demo cluster:

```bash
./gradlew :composeApp:generateScreenshots
python3 scripts/site_images.py
```

The generator captures each window's own Skia frame in-process (no Screen Recording permission), but it opens real windows, so run it on a desktop session. `scripts/site_images.py` then crops and converts the captures (Python 3.10+ and Pillow 10+); its docstring lists every file it writes.

## Tech stack

Versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

| Component | Library |
|-----------|---------|
| Language | Kotlin |
| UI framework | Compose Multiplatform, Material 3, material3-adaptive (ListDetailPaneScaffold) |
| ViewModel / lifecycle | androidx.lifecycle (multiplatform) |
| Persistence | androidx.datastore-preferences |
| Kubernetes client | fabric8 kubernetes-client (and kubernetes-server-mock for the demo cluster and tests) |
| Terminal | JediTerm (interactive pod exec) |
| YAML editor | RSyntaxTextArea, BSD-3-Clause (the separate YAML editor window) |
| Coroutines | kotlinx-coroutines (core + swing) |
| Serialization | kotlinx-serialization |
| JSONPath | json-path (custom-resource column extraction) |
| MCP server | modelcontextprotocol kotlin-sdk |
| Embedded HTTP server | Ktor (CIO + SSE + content negotiation) |
| Native interop | JNA (macOS shell `PATH` resolution) |
| Logging | Logback Classic (via SLF4J) |
| Code formatting | Spotless + ktlint |
| Build tool | Gradle, Temurin 21 (daemon JVM criteria) |
