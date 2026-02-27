# KubeKubeDashDash

A desktop Kubernetes cluster dashboard built with Jetpack Compose Multiplatform. It connects to clusters through your local kubeconfig and provides a read-oriented interface for browsing resources, viewing YAML, and streaming pod logs.

## Features

### Cluster management

- Switch between kubeconfig contexts from the sidebar
- Filter all views by namespace or browse across all namespaces
- Cluster overview with pod status breakdown, node count, and namespace count

### Resource browsing

Supported resource types:

| Category | Resources |
|----------|-----------|
| Cluster | Nodes, Namespaces, Events |
| Workloads | Pods, Deployments, StatefulSets, DaemonSets, ReplicaSets, Jobs, CronJobs |
| Config | ConfigMaps, Secrets |
| Network | Services, Ingresses, Endpoints, Network Policies |
| Storage | PersistentVolumes, PersistentVolumeClaims, StorageClasses |

All resource lists are presented in sortable tables with auto-refresh (every 5 seconds for most resources, 10 seconds for cluster-level views).

### Resource details

- Side panel with Overview and YAML tabs for inspected resources
- Full-screen YAML view with syntax highlighting and line numbers
- Labels and annotations displayed as chips

### Pod-specific

- Dedicated detail panel with Overview, YAML, and Logs tabs
- Container picker for multi-container pods
- CPU and memory usage gauges when a Metrics Server is installed
- Log viewer with text filtering, follow mode, line wrapping, and copy to clipboard

### Delete operations

Deletion is supported for Pods, Deployments, Services, ConfigMaps, Secrets, Jobs, and CronJobs. There is no create or update functionality.

### UI

- Dark theme color scheme (Material 3)
- Resizable detail panels
- Status badges with color coding (Running, Pending, Failed, etc.)

## Prerequisites

- **JDK 21** or later
- A valid `~/.kube/config` with at least one accessible cluster
- **Metrics Server** (optional) — required for CPU/memory usage data on the Pods screen

## Running

```bash
./gradlew :composeApp:run
```

The application opens a 1440×900 window and connects to the current kubeconfig context.

## Building distributable packages

```bash
# macOS
./gradlew :composeApp:packageDmg

# Windows
./gradlew :composeApp:packageMsi

# Linux
./gradlew :composeApp:packageDeb
```

## Tech stack

| Component | Library / Version |
|-----------|-------------------|
| Language | Kotlin 2.2.0 |
| UI framework | Compose Multiplatform 1.10.0 |
| Kubernetes client | fabric8 kubernetes-client 7.5.2 |
| Coroutines | kotlinx-coroutines 1.10.1 |
| Serialization | kotlinx-serialization 1.8.0 |
| Date/time | kotlinx-datetime 0.7.0 |
| Logging | slf4j-simple 2.0.16 |
| Build tool | Gradle 8.12 |

## Limitations

- Desktop only (no web or mobile targets)
- No RBAC-aware UI — errors from insufficient permissions are shown as-is
- No resource creation or editing
- Metrics require a running Metrics Server in the cluster
- Log streaming relies on fabric8's `watchLog` and may not handle all edge cases (e.g., very large log volumes)

## License

MIT
