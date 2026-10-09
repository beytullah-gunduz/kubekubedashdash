#!/usr/bin/env python3
"""UI smoke for windows, tabs and the window-wide history, driven through the Compose Hot Reload MCP.

Run it from the repository root:

    python3 scripts/ui-smoke/smoke.py [--list] [--keep-app] [SCENARIO ...]

Every scenario starts a fresh hot-run app on a fresh throwaway data directory and the
demo cluster only (an empty kubeconfig), drives it through the MCP server's semantic
tree and the hidden `ui-test:*` hook nodes the app composes in a hot run, and prints
one `PASS|FAIL <scenario>: <check>` line per check. Exit code: 0 when every check
passed, 1 when any failed or the run was aborted, 2 when it refused to start.

Python 3.10+, standard library only. See README.md for what it covers and what not.
"""
import argparse
import datetime
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
import traceback
from pathlib import Path

# WorkspaceTab.AllClusters.key
ALL_CLUSTERS = "all-clusters"
# The demo cluster's tab chips end with these (the chip text reads "D demo-cluster (mock) #1").
TAB1_SUFFIX = "demo-cluster (mock) #1"
TAB2_SUFFIX = "demo-cluster (mock) #2"
# The prefix of the hook node that reports a window's state as JSON (UiTestHooks.kt).
HOOK_STATE = "ui-test:state:"
EMPTY_KUBECONFIG_SUFFIX = "build/test-kubeconfig/empty.yaml"
MCP_TASK = ":composeApp:hotMcpServerDesktop"


class StepFailed(Exception):
    """A scenario step could not be done (a node never appeared, a wait timed out)."""


class McpError(Exception):
    """The MCP server answered with an error, timed out, or exited."""


class AbortRun(Exception):
    """The whole run must stop now (isolation gate failed, MCP server died)."""


class Ctx:
    """Mutable state of one run: paths, counters, the MCP client, the current scenario."""

    def __init__(self):
        self.repo = Path.cwd().resolve()
        self.run_dir = None
        self.java_home = ""
        self.env = {}
        self.mcp = None
        self.scenario = ""
        self.scenario_dir = None
        self.passed = 0
        self.failed = 0
        self.skipped = 0
        self.scenario_failed = 0
        self.app_pid = None
        self.launch_started = None
        self.gradle = None


CTX = Ctx()


# ---------------------------------------------------------------- results


def say(msg):
    """Print one line right away."""
    print(msg, flush=True)


def check(name, ok, detail=""):
    """Record one PASS/FAIL line for the current scenario."""
    if ok:
        CTX.passed += 1
    else:
        CTX.failed += 1
        CTX.scenario_failed += 1
    extra = f" ({detail})" if detail and not ok else ""
    say(f"{'PASS' if ok else 'FAIL'} {CTX.scenario}: {name}{extra}")
    return ok


def skip(name, why):
    """Record a SKIP line: a check that could not be exercised, which is not a failure."""
    CTX.skipped += 1
    say(f"SKIP {CTX.scenario}: {name} ({why})")


def rel(path):
    """A path relative to the repository root, for printing."""
    try:
        return str(Path(path).resolve().relative_to(CTX.repo))
    except ValueError:
        return str(path)


# ---------------------------------------------------------------- processes


def alive(pid):
    """True when the process exists and is not a zombie."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    out = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
    return bool(out) and not out.startswith("Z")


def descendants(pid, depth=4):
    """Pids below `pid` (children first level by level), found with pgrep -P."""
    out = []
    for line in subprocess.run(["pgrep", "-P", str(pid)], capture_output=True, text=True).stdout.split():
        child = int(line)
        out.append(child)
        if depth > 1:
            out.extend(descendants(child, depth - 1))
    return out


def started_since(pid, since):
    """True when `pid` started at or after the wall-clock time `since` (ps etime has a 1 s resolution)."""
    out = subprocess.run(["ps", "-o", "etime=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
    m = re.fullmatch(r"(?:(?:(\d+)-)?(\d+):)?(\d+):(\d+)", out)
    if not m:
        return False
    days, hours, minutes, seconds = (int(g or 0) for g in m.groups())
    elapsed = ((days * 24 + hours) * 60 + minutes) * 60 + seconds
    return time.time() - elapsed >= since - 2


def signal_pids(pids, sig):
    """Send `sig` to each pid that still exists."""
    for p in pids:
        try:
            os.kill(p, sig)
        except (ProcessLookupError, PermissionError):
            pass


def wait_until(fn, timeout=30.0, every=0.25):
    """Poll `fn` until it is truthy; False on timeout. A dead MCP server is re-raised, other MCP errors are retried."""
    deadline = time.monotonic() + timeout
    while True:
        try:
            if fn():
                return True
        except McpError:
            if CTX.mcp is not None and CTX.mcp.dead:
                raise
        if time.monotonic() >= deadline:
            return False
        time.sleep(every)


def must(fn, what, timeout=30.0):
    """wait_until, but a timeout aborts the scenario with a clear message."""
    if not wait_until(fn, timeout):
        raise StepFailed(f"timed out after {timeout:g}s waiting for {what}")


def run_gradle(args, log_name, timeout=900):
    """Run ./gradlew with the run's JDK, output to a log file in the scenario dir. Returns (exit code, log text)."""
    log_path = CTX.scenario_dir / log_name
    with open(log_path, "w") as log:
        proc = subprocess.Popen(
            ["./gradlew", "--console=plain", *args],
            cwd=CTX.repo, env=CTX.env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        CTX.gradle = proc
        try:
            code = proc.wait(timeout=timeout)
        except BaseException as e:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except OSError:
                pass
            if isinstance(e, subprocess.TimeoutExpired):
                raise StepFailed(f"./gradlew {' '.join(args)} did not finish within {timeout} s")
            raise
        finally:
            CTX.gradle = None
    return code, log_path.read_text(errors="replace")


def tail(text, lines=40):
    """The last lines of a log, for failure messages."""
    return "\n".join(text.splitlines()[-lines:])


# ---------------------------------------------------------------- hot-run files and the isolation gate


def pid_file():
    """The hot-run pid file the MCP server follows."""
    return CTX.repo / "composeApp" / "build" / "run" / "desktopMain" / "desktopMain.pid"


def argfile():
    """The JVM argfile of the last hotRunDesktop* task."""
    return CTX.repo / "composeApp" / "build" / "run" / "desktopMain" / "desktopMain.argfile"


def read_pid_file():
    """The pid file (a Java properties file) as a dict, or None when it is absent."""
    try:
        text = pid_file().read_text()
    except OSError:
        return None
    props = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line[0] in "#!":
            continue
        m = re.match(r"([^=:\s]+)\s*[=:]\s*(.*)$", line)
        if m:
            props[m.group(1)] = m.group(2).strip()
    return props


def pid_file_pid():
    """The pid in the pid file as an int, or None."""
    props = read_pid_file() or {}
    try:
        return int(props.get("pid", ""))
    except ValueError:
        return None


def argfile_props():
    """The -Dkey=value system properties in the argfile (quotes stripped), last one wins."""
    props = {}
    for raw in argfile().read_text(errors="replace").splitlines():
        line = raw.strip()
        if len(line) >= 2 and line[0] == '"' and line[-1] == '"':
            line = line[1:-1]
        if not line.startswith("-D"):
            continue
        key, _, val = line[2:].partition("=")
        val = val.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
            val = val[1:-1]
        props[key] = val.replace("\\\\", "\\")
    return props


def isolation_problems(data_dir):
    """What the argfile lacks to prove a demo-only, throwaway-data, hooks-on run (empty list = fine)."""
    try:
        props = argfile_props()
    except OSError as e:
        return [f"cannot read {rel(argfile())}: {e}"]
    problems = []
    kube = props.get("kubeconfig", "")
    if not os.path.normpath(kube).replace(os.sep, "/").endswith(EMPTY_KUBECONFIG_SUFFIX):
        problems.append(f"-Dkubeconfig is not the empty test kubeconfig ({EMPTY_KUBECONFIG_SUFFIX})")
    got = props.get("kkdd.dataDir", "")
    if not got or os.path.realpath(got) != os.path.realpath(str(data_dir)):
        problems.append("-Dkkdd.dataDir is not this scenario's data directory")
    if props.get("kkdd.uiTestHooks") != "true":
        problems.append("-Dkkdd.uiTestHooks=true is missing")
    if props.get("kkdd.disableCloudClis") != "true":
        problems.append("-Dkkdd.disableCloudClis=true is missing: aws/gcloud would be reachable from the app")
    return problems


def require_isolation(data_dir, when):
    """Abort the run unless the argfile proves isolation."""
    problems = isolation_problems(data_dir)
    if problems:
        raise AbortRun(f"isolation gate failed {when}: " + "; ".join(problems))


def stop_app(pid):
    """SIGTERM the app, its child processes and the pid, SIGKILL leftovers after 10 s, then wait for the files and the MCP link to clear."""
    victims = descendants(pid) + [pid]
    signal_pids(victims, signal.SIGTERM)
    wait_until(lambda: not any(alive(p) for p in victims), 10, 0.25)
    leftovers = [p for p in victims if alive(p)]
    if leftovers:
        say(f"note: SIGKILL for pids {leftovers} (they ignored SIGTERM for 10 s)")
        signal_pids(leftovers, signal.SIGKILL)
    if not wait_until(lambda: not alive(pid), 15):
        say(f"warning: app pid {pid} is still alive after SIGKILL")
    if not wait_until(lambda: not pid_file().exists(), 30):
        if pid_file_pid() == pid and not alive(pid):
            say("note: removing the stale pid file of the stopped app")
            try:
                pid_file().unlink()
            except OSError:
                pass
        else:
            say("warning: the pid file is still there after the app stopped")
    CTX.app_pid = None
    mcp = CTX.mcp
    if mcp is not None and mcp.ready and not mcp.dead:
        if not wait_until(lambda: not mcp_connected(), 30):
            say("warning: the MCP server still reports connected 30 s after the app stopped")


def launch_app(data_dir):
    """Gate, start the app with hotRunDesktopAsync on `data_dir`, return its pid."""
    prop = f"-PhotRunDataDir={data_dir}"
    code, text = run_gradle([":composeApp:hotRunDesktopArgfile", prop], "gradle-argfile.log")
    if code != 0:
        raise StepFailed("hotRunDesktopArgfile failed:\n" + tail(text))
    require_isolation(data_dir, "before the launch")
    CTX.launch_started = time.time()
    code, text = run_gradle([":composeApp:hotRunDesktopAsync", prop], "gradle-launch.log")
    if code != 0:
        raise StepFailed("hotRunDesktopAsync failed:\n" + tail(text))
    m = re.search(r"Started '[^']*' in background \((\d+)\)", text)
    if not m:
        raise StepFailed("hotRunDesktopAsync printed no \"Started ... in background (<pid>)\" line:\n" + tail(text))
    pid = int(m.group(1))
    CTX.app_pid = pid
    CTX.launch_started = None
    require_isolation(data_dir, "after the launch")
    if not wait_until(lambda: pid_file_pid() == pid, 60):
        raise StepFailed(f"the pid file never named the launched app (pid {pid})")
    return pid


def orphan_app_pid():
    """The app of a launch whose pid this run never learned (an abort during the Gradle launch, or no
    "Started" line), or None. Taken from this launch's log, else from the pid file, and only when the pid
    file was written and the process started after the launch began: a stale pid file or a recycled pid
    is never adopted. A hot run of this worktree that someone else starts during the launch would still
    look like ours; the startup guard and the README rule out a concurrent one, not a racing one."""
    since = CTX.launch_started
    if since is None:
        return None
    log = CTX.scenario_dir / "gradle-launch.log" if CTX.scenario_dir is not None else None
    if log is not None and log.is_file():
        m = re.search(r"Started '[^']*' in background \((\d+)\)", log.read_text(errors="replace"))
        if m:
            pid = int(m.group(1))
            return pid if alive(pid) and started_since(pid, since) else None
    try:
        written = pid_file().stat().st_mtime
    except OSError:
        return None
    pid = pid_file_pid()
    if pid is None or written < since - 2:
        return None
    return pid if alive(pid) and started_since(pid, since) else None


# ---------------------------------------------------------------- MCP client


class McpClient:
    """A minimal MCP stdio client for the Compose Hot Reload server: one JSON-RPC object per line."""

    def __init__(self, repo, env, err_path):
        self.repo, self.env, self.err_path = repo, env, err_path
        self.proc = None
        self.responses = {}
        self.cv = threading.Condition()
        self.closed = False
        self.ready = False
        self.write_lock = threading.Lock()
        self.next_id = 1
        self.init_id = None

    @property
    def dead(self):
        """True once the server's stdout closed or the process exited."""
        return self.closed or (self.proc is not None and self.proc.poll() is not None)

    def start(self):
        """Spawn the server (own process group) and send `initialize` without waiting for the answer."""
        self.proc = subprocess.Popen(
            ["./gradlew", "--no-daemon", "--quiet", "--console=plain", MCP_TASK],
            cwd=self.repo, env=self.env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=open(self.err_path, "w"), text=True, bufsize=1, start_new_session=True,
        )
        threading.Thread(target=self._reader, daemon=True).start()
        self.init_id = self._send_request("initialize", {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "kkdd-ui-smoke", "version": "1"},
        })

    def await_initialized(self, timeout=600):
        """Wait for the `initialize` answer once, then send the `initialized` notification."""
        if self.ready:
            return
        self._await(self.init_id, timeout, "initialize")
        self._write({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.ready = True

    def call(self, name, arguments=None, timeout=120):
        """tools/call; returns the first text content ('' if none). Raises McpError on an error result."""
        result = self.request("tools/call", {"name": name, "arguments": arguments or {}}, timeout)
        text = ""
        for item in result.get("content") or []:
            if item.get("type") == "text":
                text = item.get("text", "")
                break
        if result.get("isError"):
            raise McpError(f"{name} returned an error: {text[:300]}")
        return text

    def request(self, method, params, timeout=120):
        """One JSON-RPC request; returns its `result`."""
        rid = self._send_request(method, params)
        return self._await(rid, timeout, method)

    def stop(self):
        """Kill the server's process group (SIGTERM, then SIGKILL after 10 s)."""
        if self.proc is None:
            return
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(self.proc.pid, sig)
            except OSError:
                return
            try:
                self.proc.wait(timeout=10)
                return
            except subprocess.TimeoutExpired:
                continue

    def _send_request(self, method, params):
        rid = self.next_id
        self.next_id += 1
        self._write({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        return rid

    def _write(self, obj):
        with self.write_lock:
            try:
                self.proc.stdin.write(json.dumps(obj) + "\n")
                self.proc.stdin.flush()
            except (OSError, ValueError) as e:
                raise McpError(f"cannot write to the MCP server: {e}")

    def _reader(self):
        # Gradle may print non-JSON lines on stdout; only lines starting with { are messages.
        for line in self.proc.stdout:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if "method" in msg or msg.get("id") is None:
                continue  # a notification or a server request, not an answer to us
            with self.cv:
                self.responses[msg["id"]] = msg
                self.cv.notify_all()
        with self.cv:
            self.closed = True
            self.cv.notify_all()

    def _await(self, rid, timeout, what):
        deadline = time.monotonic() + timeout
        with self.cv:
            while rid not in self.responses:
                if self.closed:
                    raise McpError(f"the MCP server exited while waiting for {what} (see mcp.err in the run directory)")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise McpError(f"timeout after {timeout:g}s waiting for {what}")
                self.cv.wait(min(remaining, 1.0))
            msg = self.responses.pop(rid)
        if "error" in msg:
            raise McpError(f"{what}: {msg['error']}")
        return msg.get("result") or {}


def mcp_json(tool, arguments=None, timeout=120):
    """Call a tool whose text answer is JSON; None when the text is not JSON."""
    text = CTX.mcp.call(tool, arguments, timeout)
    try:
        return json.loads(text)
    except ValueError:
        return None


def mcp_connected():
    """True when the MCP server reports an attached app."""
    try:
        data = mcp_json("status", timeout=30)
    except McpError:
        return False
    return isinstance(data, dict) and bool(data.get("connected"))


# ---------------------------------------------------------------- helpers over the semantic tree


def windows():
    """The app's windows: a list of {id, title, x, y, width, height}."""
    data = mcp_json("list_windows")
    if isinstance(data, dict):
        data = data.get("windows")
    return data if isinstance(data, list) else []


def main_window():
    """The id of the first window."""
    ws = windows()
    if not ws:
        raise StepFailed("the app has no window")
    return ws[0]["id"]


def tree(win):
    """The semantic tree of a window as a list of roots (popups and dialogs are extra roots, later = on top)."""
    data = mcp_json("get_semantic_tree", {"window_id": win})
    if isinstance(data, dict):
        return [] if "error" in data else [data]
    return data if isinstance(data, list) else []


def nodes(win):
    """Every node of every root, depth first, in document order."""
    out = []
    stack = list(reversed(tree(win)))
    while stack:
        n = stack.pop()
        out.append(n)
        stack.extend(reversed(n.get("children") or []))
    return out


def enabled(node):
    """False only for a node the tree marks disabled."""
    return node.get("enabled", True)


def _hay(value):
    if value is None:
        return ""
    if isinstance(value, list):
        return " ".join(str(v) for v in value)
    return str(value)


def _matches(hay, needle, prefix, contains, suffix):
    if contains:
        return needle in hay
    if prefix:
        return hay.startswith(needle)
    if suffix:
        return hay.endswith(needle)
    return hay == needle


def find_nodes(win, desc=None, text=None, prefix=False, contains=False, suffix=False, clickable=True):
    """All nodes in the current tree whose contentDescription / text matches. Never filters by size or visibility
    (the hook nodes are 0x0). clickable=True also requires an onClick action and an enabled node."""
    assert desc is not None or text is not None
    out = []
    for n in nodes(win):
        if desc is not None and not _matches(_hay(n.get("contentDescription")), desc, prefix, contains, suffix):
            continue
        if text is not None and not _matches(_hay(n.get("text")), text, prefix, contains, suffix):
            continue
        if clickable and not ("onClick" in (n.get("actions") or []) and enabled(n)):
            continue
        out.append(n)
    return out


def find(win, desc=None, text=None, prefix=False, contains=False, suffix=False, clickable=True, which="last", timeout=10.0):
    """Poll the tree every 0.25 s for a matching node; the last match (topmost root) unless which="first". None on timeout."""
    found = []

    def probe():
        hits = find_nodes(win, desc, text, prefix, contains, suffix, clickable)
        if hits:
            found.append(hits[-1] if which == "last" else hits[0])
        return bool(hits)

    wait_until(probe, timeout)
    return found[0] if found else None


def click(win, node):
    """Run a node's semantic onClick through the MCP."""
    CTX.mcp.call("click", {"nodeId": node["id"], "window_id": win})


# Right after the first-run screen gives way to the tabs, a window briefly has no semantics owner and a
# click fails with this message although the tree reads fine; it clears within a second or two.
TRANSIENT_CLICK_ERROR = "No semantic owners available"


def _click_where(win, label, timeout=10.0, **kw):
    """Find the node, click it; on the transient no-owner error, re-find and retry until `timeout`."""
    deadline = time.monotonic() + timeout
    while True:
        node = find(win, **kw)
        if node is None:
            raise StepFailed(f"no clickable node with {label} in window {win}")
        try:
            click(win, node)
            return
        except McpError as e:
            if TRANSIENT_CLICK_ERROR not in str(e) or time.monotonic() >= deadline or (CTX.mcp is not None and CTX.mcp.dead):
                raise
            time.sleep(0.5)


def click_desc(win, name, prefix=False):
    """Re-find a node by contentDescription right before clicking it (node ids change on recomposition)."""
    _click_where(win, f"desc {name!r}", desc=name, prefix=prefix)


def click_text(win, name, prefix=False, contains=False, suffix=False):
    """Re-find a node by text right before clicking it."""
    _click_where(win, f"text {name!r}", text=name, prefix=prefix, contains=contains, suffix=suffix)


def state(win):
    """The window's `ui-test:state:` hook, parsed, or None."""
    for n in nodes(win):
        d = n.get("contentDescription")
        if isinstance(d, str) and d.startswith(HOOK_STATE):
            try:
                return json.loads(d[len(HOOK_STATE):])
            except ValueError:
                return None
    return None


def st(win, timeout=3.0):
    """state(win), retried for up to `timeout` s (a read can miss the node while the window recomposes),
    or an empty dict when there is none."""
    deadline = time.monotonic() + timeout
    while True:
        try:
            s = state(win)
        except McpError:
            if CTX.mcp is not None and CTX.mcp.dead:
                raise
            s = None
        if s is not None or time.monotonic() >= deadline:
            return s or {}
        time.sleep(0.2)


def wait_state(win, predicate, timeout=10.0):
    """Poll until `predicate(state dict)` is truthy (one tree read per poll); False on timeout."""
    return wait_until(lambda: predicate(st(win)), timeout)


def must_state(win, predicate, what, timeout=30.0):
    """wait_state, but a timeout aborts the scenario with a clear message. Returns the state that satisfied
    `predicate`: a later read can come back empty while the window recomposes, so use this snapshot."""
    seen = {}

    def probe():
        s = st(win)
        if predicate(s):
            seen.update(s)
            return True
        return False

    must(probe, what, timeout)
    return seen


def settle(win, timeout=30.0):
    """After a tab switch: wait until the pager shows the active tab and its slide is over (only one tab's
    sidebar is composed), so a click on a sidebar row cannot land on the tab sliding away."""
    must(lambda: (lambda s: s.get("active") is not None and s.get("active") == s.get("page"))(st(win))
         and len(find_nodes(win, text="Topology")) == 1, "the pager to settle on the active tab", timeout)


def window_for(predicate, timeout=10.0):
    """The id of the window whose state satisfies `predicate`, or None."""
    found = []

    def probe():
        for w in windows():
            try:
                s = state(w["id"])
            except McpError:
                continue
            if s and predicate(s):
                found.append(w["id"])
                return True
        return False

    wait_until(probe, timeout)
    return found[0] if found else None


def shot(win, name):
    """take_screenshot into the scenario dir; records a check that the file exists."""
    path = CTX.scenario_dir / f"{name}.png"
    CTX.mcp.call("take_screenshot", {"window_id": win, "save_to": str(path)}, timeout=180)
    check(f"screenshot {name}.png saved", path.is_file() and path.stat().st_size > 0)


def dump_failure():
    """After a failed scenario: save every window's tree and a screenshot next to the other artifacts."""
    try:
        for w in windows():
            wid = re.sub(r"[^A-Za-z0-9_-]", "_", str(w["id"]))
            (CTX.scenario_dir / f"failure-{wid}.json").write_text(json.dumps(tree(w["id"]), indent=1))
            CTX.mcp.call("take_screenshot", {"window_id": w["id"], "save_to": str(CTX.scenario_dir / f"failure-{wid}.png")}, timeout=180)
    except Exception:
        pass


# ---------------------------------------------------------------- shared scenario steps


def two_demo_tabs(win):
    """From the first-run screen: open the demo cluster, then a second one. Returns (tab 1 key, tab 2 key)."""
    must(lambda: find(win, text="Try demo cluster", prefix=True, timeout=0), "the first-run screen", 120)
    click_text(win, "Try demo cluster", prefix=True)
    first = must_state(win, lambda s: str(s.get("active", "")).startswith("cluster:") and s.get("firstRun") is False
                       and s.get("screen") == "Cluster Overview", "the first demo cluster's overview")
    tab1 = first["active"]
    click_desc(win, "Open another cluster")
    click_text(win, "In-memory mock cluster with sample data", contains=True)
    second = must_state(win, lambda s: len(s.get("tabs", [])) == 2 and s.get("active") != tab1
                        and s.get("screen") == "Cluster Overview", "the second demo cluster's overview")
    settle(win)
    return tab1, second["active"]


def open_all_clusters(win):
    """Open the All Clusters tab from the cluster picker."""
    click_desc(win, "Open another cluster")
    click_text(win, "All Clusters view", prefix=True)
    must_state(win, lambda s: s.get("active") == ALL_CLUSTERS, "the All Clusters tab to become active")


def tear_out_all_clusters(win):
    """Two demo tabs + All Clusters, then tear All Clusters out. Returns the new window's id (or None)."""
    two_demo_tabs(win)
    open_all_clusters(win)
    click_desc(win, "ui-test:tear-out:" + ALL_CLUSTERS)
    must(lambda: len(windows()) == 2, "a second window after the tear-out")
    return window_for(lambda s: s.get("tabs") == [ALL_CLUSTERS])


# ---------------------------------------------------------------- scenarios

SCENARIOS = []


def scenario(name):
    """Register a scenario function, in run order."""
    def register(fn):
        SCENARIOS.append((name, fn))
        return fn
    return register


@scenario("first-run-title-bar")
def s1_first_run_title_bar():
    """The first-run screen has a title bar with window buttons and disabled Back/Forward."""
    win = main_window()
    must(lambda: find(win, text="Try demo cluster", prefix=True, timeout=0), "the first-run screen", 120)
    for d in ("Close window", "Minimize window", "Maximize window"):
        check(f"{d!r} is in the title bar", find(win, desc=d, clickable=False, timeout=5) is not None)
    for d in ("Back", "Forward"):
        n = find(win, desc=d, clickable=False, timeout=5)
        check(f"{d!r} is disabled", n is not None and not enabled(n), "node missing" if n is None else "")
    check("state.firstRun is true", st(win).get("firstRun") is True)
    # With the cloud CLIs off, the screen must not offer a discovery that would run aws/gcloud.
    offered = [_hay(n.get("text")) for n in find_nodes(win, text="Discover", prefix=True, clickable=False)]
    check("the first-run screen offers no EKS/GKE discovery", not offered, f"found {offered}")
    shot(win, "first-run")


@scenario("discovery-splash-title-bar")
def s2_discovery_splash_title_bar():
    """The EKS/GKE discovery splash (forced by a hook) keeps the title bar."""
    win = main_window()
    must(lambda: find(win, text="Try demo cluster", prefix=True, timeout=0), "the first-run screen", 120)
    click_desc(win, "ui-test:discovery-splash")

    def splash_is_up():
        ns = nodes(win)
        has_state = any(str(n.get("contentDescription") or "").startswith(HOOK_STATE) for n in ns)
        return has_state and not any(_hay(n.get("text")).startswith("Try demo cluster") for n in ns)

    must(splash_is_up, "the first-run screen to give way to the discovery splash")
    check("'Close window' is in the title bar", find(win, desc="Close window", clickable=False, timeout=5) is not None)
    check("state.firstRun is true", st(win).get("firstRun") is True)
    shot(win, "splash")


@scenario("tear-out-all-clusters")
def s3_tear_out_all_clusters():
    """Tearing All Clusters out opens it in its own window, with a title bar, active."""
    win = main_window()
    new = tear_out_all_clusters(win)
    check("a window shows only the All Clusters tab", new is not None)
    if new is not None:
        s = st(new)
        check("the new window's active tab is All Clusters", s.get("active") == ALL_CLUSTERS, f"active={s.get('active')}")
        check("the new window is not on the first-run screen", s.get("firstRun") is False)
        check("the new window has a 'Close window' button", find(new, desc="Close window", clickable=False, timeout=5) is not None)
        shot(new, "tear-out-new")
    old = st(win)
    check("the old window no longer has All Clusters", ALL_CLUSTERS not in old.get("tabs", []), f"tabs={old.get('tabs')}")
    check("the old window's active tab is a cluster", str(old.get("active", "")).startswith("cluster:"), f"active={old.get('active')}")
    shot(win, "tear-out-old")


@scenario("merge-all-clusters-back")
def s4_merge_all_clusters_back():
    """Merging the torn-out All Clusters tab back leaves one window with it first and active."""
    win = main_window()
    new = tear_out_all_clusters(win)
    if new is None:
        raise StepFailed("no window with only the All Clusters tab after the tear-out")
    click_desc(new, "ui-test:merge:" + ALL_CLUSTERS)
    must(lambda: len(windows()) == 1, "one window after the merge")
    left = windows()[0]["id"]
    s = must_state(left, lambda s: s.get("tabs"), "the remaining window's state")
    check("All Clusters is the first tab of the remaining window", (s.get("tabs") or [None])[0] == ALL_CLUSTERS, f"tabs={s.get('tabs')}")
    check("All Clusters is active in the remaining window", s.get("active") == ALL_CLUSTERS, f"active={s.get('active')}")
    shot(left, "merged")


@scenario("history-stays-in-its-window")
def s5_history_stays_in_its_window():
    """After a tab is torn out, neither window's Back/Forward points at a tab it does not hold."""
    win = main_window()
    two_demo_tabs(win)
    click_text(win, "Nodes")
    must_state(win, lambda s: s.get("screen") == "Nodes", "the Nodes screen")
    moved = st(win)["active"]
    click_text(win, TAB1_SUFFIX, suffix=True)
    must_state(win, lambda s: s.get("active") != moved, "tab 1 to become active")
    settle(win)
    click_text(win, "Pods")
    must_state(win, lambda s: s.get("screen") == "Pods", "the Pods screen")
    click_text(win, TAB2_SUFFIX, suffix=True)
    must_state(win, lambda s: s.get("active") == moved, "tab 2 to become active again")
    settle(win)
    click_desc(win, "ui-test:tear-out:" + moved)
    must(lambda: len(windows()) == 2, "a second window after the tear-out")
    src = window_for(lambda s: moved not in s.get("tabs", []))
    dst = window_for(lambda s: moved in s.get("tabs", []))
    if src is None or dst is None:
        raise StepFailed(f"could not tell the source and the torn-out window apart (src={src}, dst={dst})")
    s_src = st(src)
    check("the source window's Back stack has no entry for the moved tab", moved not in s_src.get("back", []), f"back={s_src.get('back')}")
    check("the source window's Forward stack has no entry for the moved tab", moved not in s_src.get("forward", []), f"forward={s_src.get('forward')}")
    check("the torn-out window starts with an empty Back stack", st(dst).get("back") == [], f"back={st(dst).get('back')}")
    for i in range(1, 6):
        back = find(src, desc="Back", clickable=False, timeout=3)
        if back is None or not enabled(back):
            break
        before = len(st(src).get("back", []))
        click(src, back)
        wait_state(src, lambda s: len(s.get("back", [])) < before, 5)
        now = st(src)
        check(f"Back #{i} in the source window does not land on the moved tab", now.get("active") != moved, f"active={now.get('active')}")
    shot(src, "source")
    shot(dst, "torn-out")


@scenario("rapid-back-forward")
def s6_rapid_back_forward():
    """Four Backs then four Forwards without pausing leave the pager on the active tab, at the newest place."""
    win = main_window()
    tab1, tab2 = two_demo_tabs(win)
    click_text(win, "Pods")
    must_state(win, lambda s: s.get("screen") == "Pods", "the Pods screen")
    click_text(win, "Nodes")
    must_state(win, lambda s: s.get("screen") == "Nodes", "the Nodes screen")
    click_text(win, TAB1_SUFFIX, suffix=True)
    must_state(win, lambda s: s.get("active") != tab2, "tab 1 to become active")
    settle(win)
    click_text(win, "Deployments")
    must_state(win, lambda s: s.get("screen") == "Deployments", "the Deployments screen")
    for _ in range(4):
        click_desc(win, "Back")
    for _ in range(4):
        click_desc(win, "Forward")
    settled = wait_state(win, lambda s: s.get("active") is not None and s.get("active") == s.get("page"), 10)
    s = st(win)
    check("the pager shows the active tab after the rapid presses", settled, f"active={s.get('active')}, page={s.get('page')}")
    fwd = find(win, desc="Forward", clickable=False, timeout=5)
    check("back at the newest place: Deployments, Forward disabled",
          s.get("screen") == "Deployments" and fwd is not None and not enabled(fwd),
          f"screen={s.get('screen')}, forward={'missing' if fwd is None else enabled(fwd)}")
    err = mcp_json("get_ui_error", {"window_id": win})
    check("get_ui_error reports no UI error", isinstance(err, dict) and err.get("hasError") is False, f"answer={err}")
    shot(win, "after-rapid")


@scenario("history-shortcut-tab-kinds")
def s7_history_shortcut_tab_kinds():
    """The Cmd/Ctrl+[ handler walks history on a cluster tab and on All Clusters, and passes through on a terminal tab."""
    win = main_window()
    two_demo_tabs(win)
    click_text(win, "Nodes")
    must_state(win, lambda s: s.get("screen") == "Nodes", "the Nodes screen")
    click_desc(win, "ui-test:back")
    ok = wait_state(win, lambda s: s.get("lastShortcut") == "handled" and s.get("screen") == "Cluster Overview")
    check("back on a cluster tab is handled and returns to the overview", ok, f"state={st(win)}")

    open_all_clusters(win)
    click_desc(win, "ui-test:back")
    ok = wait_state(win, lambda s: s.get("lastShortcut") == "handled" and str(s.get("active", "")).startswith("cluster:"))
    check("back on the All Clusters tab is handled and returns to a cluster", ok, f"state={st(win)}")

    click_text(win, "Pods")
    must_state(win, lambda s: s.get("screen") == "Pods", "the Pods screen")
    name = "back on a terminal tab passes through"
    # The first "⋮" is the table header's columns menu, the second is the first row's.
    if not wait_until(lambda: len(find_nodes(win, text="⋮")) >= 2, 15):
        return skip(name, "no row menu found")
    menus = find_nodes(win, text="⋮")
    if len(menus) < 2:
        return skip(name, "the row menu vanished before it was clicked")
    click(win, menus[1])
    opener = find(win, text="Open terminal", timeout=10)
    if opener is None:
        return skip(name, "no 'Open terminal' entry in the row menu")
    click(win, opener)
    if not wait_state(win, lambda s: str(s.get("active", "")).startswith("terminal:")):
        return skip(name, "no terminal tab opened (a pod with several containers opens a picker)")
    click_desc(win, "ui-test:back")
    ok = wait_state(win, lambda s: s.get("lastShortcut") == "passed")
    s = st(win)
    check(name, ok and str(s.get("active", "")).startswith("terminal:"), f"state={s}")
    shot(win, "terminal")


SHORT_WINDOW = (1000, 600)


def root_height(win, timeout=10.0):
    """The window root's height in px, waiting out a tree that is briefly empty."""
    found = []

    def probe():
        roots = tree(win)
        height = ((roots[0].get("bounds") or {}).get("height") if roots else None)
        if height:
            found.append(height)
        return bool(height)

    must(probe, "the window's semantic tree", timeout)
    return found[-1]


def window_height_pt(win, timeout=10.0):
    """The window's height in points from list_windows, waiting out an empty answer."""
    found = []

    def probe():
        hits = [w["height"] for w in windows() if w.get("id") == win and w.get("height")]
        found.extend(hits)
        return bool(hits)

    must(probe, "the window in list_windows", timeout)
    return found[-1]


@scenario("cluster-selector-short-window")
def s8_cluster_selector_short_window():
    """In a short window the cluster picker still shows its footer rows (All Clusters view, Discover EKS/GKE)."""
    win = main_window()
    two_demo_tabs(win)  # "All Clusters view" is offered from two cluster tabs on: the tallest footer
    width, height = SHORT_WINDOW
    before = root_height(win)
    CTX.mcp.call("resize_window", {"window_id": win, "width": width, "height": height})
    must(lambda: (tree(win) or [{}])[0].get("bounds", {}).get("height") not in (None, before),
         f"the window to shrink to {width}x{height}")
    click_desc(win, "Open another cluster")
    must(lambda: find(win, text="Select Cluster", prefix=True, clickable=False, timeout=0), "the cluster picker")
    window_height = root_height(win)
    scale = window_height / window_height_pt(win)
    all_row = find(win, text="All Clusters view", prefix=True, clickable=False, timeout=5)
    ab = (all_row or {}).get("bounds") or {}
    all_bottom = ab.get("y", 0) + ab.get("height", 0)
    check(f"'All Clusters view' is visible in a {width}x{height} window",
          all_row is not None and ab.get("height", 0) > 0 and all_bottom <= window_height,
          f"bounds={ab}, window height={window_height}")
    card = find(win, text="Select Cluster", prefix=True, clickable=False, timeout=5)
    cb = (card or {}).get("bounds") or {}
    card_bottom = cb.get("y", 0) + cb.get("height", 0)
    for label in ("Discover EKS clusters", "Discover GKE clusters"):
        node = find(win, text=label, prefix=True, clickable=False, timeout=2)
        if node is not None:
            b = node.get("bounds") or {}
            visible = b.get("height", 0) > 0 and b.get("y", 0) + b.get("height", 0) <= window_height
            detail = f"bounds={b}, window height={window_height}"
        else:
            # With the cloud CLIs off the row is not clickable, so its text merges into the card's
            # node: it is on screen when the card holds it, fits the window and keeps room for the
            # two Discover rows (about 56 dp each) under a laid-out All Clusters view row (a
            # squeezed footer leaves that row 0x0, which would make the room look like the card).
            room = card_bottom - all_bottom
            visible = (card is not None and label in _hay(card.get("text")) and card_bottom <= window_height
                       and ab.get("height", 0) > 0 and room >= 100 * scale)
            detail = f"card={cb}, room under All Clusters view={room}px, window height={window_height}"
        check(f"'{label}' is visible in a {width}x{height} window", visible, detail)
    shot(win, "selector-short")


MAC_TITLE_BAR_DP = 38  # TitleBar.kt titleBarHeight() on macOS at the default density


def title_bar_node(win, desc, bar_px):
    """The node with this exact contentDescription inside the title bar band, or None."""
    hits = [n for n in find_nodes(win, desc=desc, clickable=False) if (n.get("bounds") or {}).get("y", bar_px) < bar_px]
    return hits[-1] if hits else None


@scenario("modal-keeps-title-bar")
def s9_modal_keeps_title_bar():
    """An open modal (the cluster picker) sits under the title bar, whose controls go inert until it closes."""
    win = main_window()
    must(lambda: find(win, text="Try demo cluster", prefix=True, timeout=0), "the first-run screen", 120)
    click_text(win, "Try demo cluster", prefix=True)
    must_state(win, lambda s: s.get("screen") == "Cluster Overview", "the demo cluster's overview")
    # Short enough that a picker laid out over the whole window would reach the title bar.
    width, height = 1000, 280
    before = root_height(win)
    CTX.mcp.call("resize_window", {"window_id": win, "width": width, "height": height})
    must(lambda: (tree(win) or [{}])[0].get("bounds", {}).get("height") not in (None, before),
         f"the window to shrink to {width}x{height}")
    root_px = root_height(win)
    window_pt = window_height_pt(win)
    bar_px = MAC_TITLE_BAR_DP * root_px / window_pt

    # One step of history, so Back has somewhere to go.
    click_text(win, "Nodes")
    must_state(win, lambda s: s.get("screen") == "Nodes", "the Nodes screen")
    settings = title_bar_node(win, "Settings", bar_px)
    check("'Settings' is enabled before the picker opens", settings is not None and enabled(settings), f"node={settings}")
    back = title_bar_node(win, "Back", bar_px)
    check("'Back' is enabled before the picker opens", back is not None and enabled(back), f"node={back}")

    click_desc(win, "Open another cluster")
    must(lambda: find(win, text="Select Cluster", prefix=True, clickable=False, timeout=0), "the cluster picker")
    card = find(win, text="Select Cluster", prefix=True, clickable=False, timeout=5)
    top = ((card or {}).get("bounds") or {}).get("y", -1)
    check("the picker starts under the title bar", top >= bar_px - 1, f"card top={top}px, title bar={bar_px:g}px")
    settings = title_bar_node(win, "Settings", bar_px)
    check("'Settings' is disabled while the picker is open", settings is not None and not enabled(settings), f"node={settings}")
    back = title_bar_node(win, "Back", bar_px)
    check("'Back' is disabled while the picker is open", back is not None and not enabled(back), f"node={back}")
    click_desc(win, "ui-test:back")
    ok = wait_state(win, lambda s: s.get("lastShortcut") == "passed")
    check("Cmd/Ctrl+[ passes through while the picker is open", ok, f"state={st(win)}")
    shot(win, "picker-open")

    click_desc(win, "Close")
    must(lambda: not find_nodes(win, text="Select Cluster", prefix=True, clickable=False), "the picker to close")
    settings = title_bar_node(win, "Settings", bar_px)
    check("'Settings' is enabled again once the picker is closed", settings is not None and enabled(settings), f"node={settings}")
    back = title_bar_node(win, "Back", bar_px)
    check("'Back' is enabled again once the picker is closed", back is not None and enabled(back), f"node={back}")


# ---------------------------------------------------------------- runner


def resolve_java_home():
    """JAVA_HOME from the environment, else JDK 21 from /usr/libexec/java_home (macOS)."""
    if os.environ.get("JAVA_HOME"):
        return os.environ["JAVA_HOME"]
    try:
        return subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def on_sigterm(signum, frame):
    """Turn SIGTERM into KeyboardInterrupt so the cleanup in main() runs."""
    raise KeyboardInterrupt()


def refuse(code, msg):
    """Print a refusal on stderr and exit."""
    print(msg, file=sys.stderr, flush=True)
    sys.exit(code)


def run_scenario(name, fn, keep_app):
    """Run one scenario on a fresh app. Returns False when the run should stop (app kept for inspection)."""
    CTX.scenario = name
    CTX.scenario_failed = 0
    CTX.scenario_dir = CTX.run_dir / name
    CTX.scenario_dir.mkdir(parents=True)
    data_dir = CTX.scenario_dir / "data"
    say(f"--- {name}")
    try:
        launch_app(data_dir)
        CTX.mcp.await_initialized()
        must(lambda: mcp_connected() and windows(), "the MCP server to attach to the app with a window", 120)
        # The isolation gate has passed twice for this launch: only now is a tree read.
        fn()
    except StepFailed as e:
        check("scenario ran to the end", False, str(e))
    except McpError as e:
        check("scenario ran to the end", False, f"MCP error: {e}")
        if CTX.mcp.dead:
            raise AbortRun("the MCP server exited (see mcp.err in the run directory)")
    except AbortRun:
        raise
    except Exception:
        check("scenario ran to the end", False, "unexpected error:\n" + traceback.format_exc())
    finally:
        if CTX.app_pid is None:
            CTX.app_pid = orphan_app_pid()
        CTX.launch_started = None
        failed = CTX.scenario_failed > 0
        if failed and CTX.app_pid is not None and CTX.mcp.ready and not CTX.mcp.dead:
            dump_failure()
        if CTX.app_pid is not None and not (failed and keep_app):
            stop_app(CTX.app_pid)
    if CTX.scenario_failed and keep_app and CTX.app_pid is not None:
        say(f"--keep-app: the app of {name} is still running (pid {CTX.app_pid}); stop it with: kill {CTX.app_pid}")
        return False
    return True


def parse_args():
    """The command line."""
    ap = argparse.ArgumentParser(description="UI smoke for windows, tabs and history (run from the repository root).")
    ap.add_argument("--list", action="store_true", help="print the scenario names and exit")
    ap.add_argument("--keep-app", action="store_true", help="leave the app of the first failing scenario running (prints its pid)")
    ap.add_argument("scenarios", nargs="*", metavar="SCENARIO", help="scenario names (default: all, in order)")
    return ap.parse_args()


def main():
    """Entry point; returns the process exit code."""
    args = parse_args()
    names = [n for n, _ in SCENARIOS]
    if args.list:
        print("\n".join(names))
        return 0
    unknown = [n for n in args.scenarios if n not in names]
    if unknown:
        refuse(2, f"unknown scenario(s): {', '.join(unknown)}\nknown: {', '.join(names)}")
    if not ((CTX.repo / "gradlew").is_file() and (CTX.repo / "composeApp").is_dir()):
        refuse(2, "run this from the repository root (./gradlew and composeApp/ must be in the current directory)")
    if os.environ.get("ORG_GRADLE_PROJECT_hotRunCloudClis"):
        refuse(2, "ORG_GRADLE_PROJECT_hotRunCloudClis is set: the app could run aws/gcloud against real accounts; unset it")
    if os.environ.get("ORG_GRADLE_PROJECT_hotRunKubeconfig"):
        refuse(2, "ORG_GRADLE_PROJECT_hotRunKubeconfig is set: a hot run could read a kubeconfig other than the empty one; unset it")
    CTX.java_home = resolve_java_home()
    if not CTX.java_home:
        refuse(2, "no JDK 21: set JAVA_HOME (or run on macOS with a JDK 21 installed)")
    other = pid_file_pid()
    if other is not None and alive(other):
        refuse(2, f"another hot run of this worktree is up (pid {other}): the MCP server follows the same pid file and would attach to it; stop it first")
    CTX.env = dict(os.environ, JAVA_HOME=CTX.java_home)
    CTX.env.pop("KUBECONFIG", None)

    selected = [(n, f) for n, f in SCENARIOS if not args.scenarios or n in args.scenarios]
    CTX.run_dir = CTX.repo / "build" / "ui-smoke" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    CTX.run_dir.mkdir(parents=True)
    say(f"run directory: {rel(CTX.run_dir)}")
    signal.signal(signal.SIGTERM, on_sigterm)

    aborted = False
    kept = False
    CTX.mcp = McpClient(CTX.repo, CTX.env, CTX.run_dir / "mcp.err")
    try:
        CTX.mcp.start()
        for name, fn in selected:
            if CTX.mcp.dead:
                raise AbortRun("the MCP server exited (see mcp.err in the run directory)")
            if not run_scenario(name, fn, args.keep_app):
                kept = True
                break
    except AbortRun as e:
        aborted = True
        say(f"ABORT: {e}")
    except KeyboardInterrupt:
        aborted = True
        say("ABORT: interrupted")
    except McpError as e:
        aborted = True
        say(f"ABORT: MCP error: {e}")
    finally:
        if CTX.gradle is not None:
            try:
                os.killpg(CTX.gradle.pid, signal.SIGTERM)
            except OSError:
                pass
        if CTX.app_pid is None and not kept:
            CTX.app_pid = orphan_app_pid()
        if CTX.app_pid is not None and not kept:
            try:
                stop_app(CTX.app_pid)
            except Exception as e:
                say(f"warning: could not stop the app cleanly: {e}")
        CTX.mcp.stop()

    if CTX.skipped:
        say(f"{CTX.skipped} skipped")
    say(f"{CTX.passed} passed, {CTX.failed} failed")
    return 1 if aborted or CTX.failed else 0


if __name__ == "__main__":
    sys.exit(main())
