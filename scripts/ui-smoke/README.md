# UI smoke: windows, tabs and history

Drives a real hot-run copy of the app through the Compose Hot Reload MCP server and checks
what the user sees after a tab is torn out, merged back, or walked through with Back/Forward.
Python 3.10+, standard library only. It does not depend on anything outside this repository.

## What it covers

- The first-run screen and the EKS/GKE discovery splash have a title bar with window buttons
  and a disabled Back/Forward.
- Tearing the All Clusters tab out into its own window, and merging it back.
- The window-wide history after a tab moved to another window: neither window's Back/Forward
  points at a tab it does not hold.
- Four Back then four Forward presses with no pause: the pager ends on the active tab, at the
  newest place, with no UI error.
- The Cmd/Ctrl+[ handler on a cluster tab, on All Clusters, and (when a terminal can be
  opened) on a terminal tab, where it must pass through to the shell.
- The cluster picker in a 1000x600 window: its footer rows (All Clusters view, Discover EKS,
  Discover GKE) keep their room instead of being squeezed out by the list.
- An open modal (the cluster picker, in a 1000x280 window) sits under the title bar, the title
  bar's Settings and Back buttons are disabled and Cmd/Ctrl+[ passes through until the picker
  closes.

Scenarios: `first-run-title-bar`, `discovery-splash-title-bar`, `tear-out-all-clusters`,
`merge-all-clusters-back`, `history-stays-in-its-window`, `rapid-back-forward`,
`history-shortcut-tab-kinds`, `cluster-selector-short-window`, `modal-keeps-title-bar`.

## What it does not cover

- The drag gesture itself. The MCP has no drag, so hidden test hooks call the same code a
  drag ends in (`WorkspaceManager.handleChipRelease` / `handleTabRelease`).
- Real key events. The MCP has no keyboard; a hook calls the same function the key handler
  calls for Cmd/Ctrl+[ and Cmd/Ctrl+].
- Pixels, beyond the screenshots it saves for you to look at (Retro, high contrast, ...).
- Windows and Linux. The window-button selectors ("Close window", "Minimize window",
  "Maximize window") are macOS-only, and the JDK lookup uses `/usr/libexec/java_home`.

## Running it

From the repository root, on a desktop session (tested on macOS) with JDK 21:

    python3 scripts/ui-smoke/smoke.py                    # every scenario, in order
    python3 scripts/ui-smoke/smoke.py tear-out-all-clusters rapid-back-forward
    python3 scripts/ui-smoke/smoke.py --list             # the scenario names, nothing is launched
    python3 scripts/ui-smoke/smoke.py --keep-app NAME    # leave the app of a failing scenario running

`JAVA_HOME` is used if set, otherwise `/usr/libexec/java_home -v 21`. Every scenario compiles
and launches a fresh app, so a full run takes a while and opens real windows; leave the mouse
and keyboard alone while it runs.

Output is one line per check, `PASS|FAIL <scenario>: <check>` (`SKIP` for a check that could
not be exercised, for example when the pod has several containers and no terminal opens),
then `N passed, M failed`. Exit code: 0 all passed, 1 a check failed or the run aborted,
2 it refused to start.

## Isolation

- Each scenario launches the app with `-PhotRunDataDir=<run>/<scenario>/data`, a data
  directory that did not exist before. Your preferences, session and logs are not touched.
- The kubeconfig stays the empty test one, so the demo cluster is the only cluster.
- The cloud CLIs are off: hot runs start with `-Dkkdd.disableCloudClis=true`, which makes `aws`,
  `gcloud`, `gke-gcloud-auth-plugin`, `az` and `kubelogin` resolve as missing in the app. No click,
  intended or stray, can start a real EKS/GKE discovery against your accounts; the first-run
  screen shows no Discover button, and `first-run-title-bar` checks that. The discovery splash is
  forced through a hook.
- Before the app starts and again after, the runner reads the JVM argfile and aborts unless it
  holds the empty kubeconfig, this scenario's data directory, `-Dkkdd.uiTestHooks=true` and
  `-Dkkdd.disableCloudClis=true`. No semantic tree is read before that. It refuses to start when
  `ORG_GRADLE_PROJECT_hotRunKubeconfig` or `ORG_GRADLE_PROJECT_hotRunCloudClis` is set.
- It refuses to start while another hot run of the same worktree is up (the MCP server follows
  one pid file and would attach to it). Stop other Hot Reload MCP servers of this worktree
  first.
- It only ever signals processes it started: the app, the processes below it, and the process
  group of the Gradle client that runs the MCP server. The server's own JVM (a single-use Gradle
  daemon in another process group) is not signalled; it exits when that client goes away. An app
  whose launch was interrupted is stopped too, but only when it started after that launch began.
  There are no pattern kills.

## Where things go

`build/ui-smoke/<YYYYmmdd-HHMMSS>/` (git-ignored):

- `mcp.err` is the MCP server's stderr for the whole run.
- `<scenario>/*.png` are the screenshots; after a failing scenario also `failure-*.json`
  (the semantic trees) and `failure-*.png`.
- `<scenario>/gradle-argfile.log` and `gradle-launch.log` are the Gradle output of the launch.
- `<scenario>/data/` is the throwaway data directory.

## The test hooks

The app composes invisible, zero-size nodes named `ui-test:*` (tear a tab out, merge it into
the other window, Back, Forward, force the discovery splash, and one that reports the window's
state as JSON) only when `-Dkkdd.uiTestHooks=true`. The `hotRun*` Gradle tasks set it; release
builds, tests and the screenshot generator never do. See `UiTestHooks.kt`.
