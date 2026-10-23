"""Coding-agent status for the watch face: Claude Code and Codex hooks record it, `gillspeak agents serve` shares it.

Stdlib only: the hooks run on every tool call, so they must start fast and never fail the agent.
"""

from __future__ import annotations

import contextlib
import datetime as dt
import fcntl
import hmac
import json
import os
import secrets
import socket
import sys
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

from . import paths

SCHEMA_VERSION = 1
DEFAULT_PORT = 8765
TEXT_LIMIT = 60
KEEP_SECONDS = 12 * 3600  # a session nobody has heard from in 12 h is gone
SHOW_DONE_SECONDS = 2 * 3600  # finished sessions stay on the watch this long
FINISH_LOG_LIMIT = 500

TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"  # no 0/o or 1/l/i: it's typed on a watch keyboard
TOKEN_LENGTH = 12

# Tools that stop and wait for you while they run: the session needs you until the tool returns.
_ASKS_YOU = {"AskUserQuestion": "Asking you a question", "ExitPlanMode": "Plan ready for review"}

# What the watch shows first.
_STATE_ORDER = {"waiting": 0, "running": 1, "done": 2, "idle": 3}


def state_file() -> Path:
    return paths.data_dir() / "agents.json"


def token_file() -> Path:
    return paths.config_dir() / "watch-token"


# -- the shared state file ----------------------------------------------------------------


def _empty() -> dict[str, Any]:
    return {"v": SCHEMA_VERSION, "sessions": {}, "finished": []}


def load_state() -> dict[str, Any]:
    try:
        data = json.loads(state_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return _empty()
    if not isinstance(data, dict) or not isinstance(data.get("sessions"), dict):
        return _empty()
    data.setdefault("finished", [])
    return data


def _write_state(state: dict[str, Any]) -> None:
    path = state_file()
    tmp = path.with_suffix(".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(state, f, separators=(",", ":"))
    os.replace(tmp, path)


@contextlib.contextmanager
def _locked_state() -> Iterator[dict[str, Any]]:
    """Read, let the caller change, and write the state while holding a lock: several agents' hooks run at once."""
    path = state_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path.with_suffix(".lock"), "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state = load_state()
        yield state
        _write_state(state)


def _short(text: Any, limit: int = TEXT_LIMIT) -> str:
    if not isinstance(text, str):
        return ""
    line = " ".join(text.split())
    return line if len(line) <= limit else line[: limit - 1].rstrip() + "…"


def _session(state: dict[str, Any], agent: str, sid: str, cwd: Any, now: float) -> dict[str, Any]:
    key = f"{agent}:{sid}"
    s = state["sessions"].get(key)
    if s is None:
        s = {"agent": agent, "id": sid, "state": "idle", "task": "", "activity": "", "detail": "",
             "done": 0, "total": 0, "started": now}
        state["sessions"][key] = s
    if isinstance(cwd, str) and cwd:
        s["project"] = Path(cwd).name
    s["updated"] = now
    return s


def _finish(state: dict[str, Any], s: dict[str, Any], now: float) -> None:
    if s["state"] != "done":
        state["finished"] = [*state["finished"][-(FINISH_LOG_LIMIT - 1):], now]
    s["state"] = "done"
    s["detail"] = ""


def _prune(state: dict[str, Any], now: float) -> None:
    state["sessions"] = {k: s for k, s in state["sessions"].items() if now - s.get("updated", 0) < KEEP_SECONDS}
    state["finished"] = [t for t in state["finished"] if now - t < 2 * 86400]


# -- Claude Code --------------------------------------------------------------------------


def _describe_tool(name: str, args: dict[str, Any]) -> tuple[str, str]:
    """A short activity for the watch, and the detail shown if this tool waits for approval."""
    path = args.get("file_path") or args.get("notebook_path") or args.get("path")
    base = Path(path).name if isinstance(path, str) and path else ""
    if name == "Bash":
        command = _short(args.get("command"))
        return (_short(args.get("description")) or "Running a command"), command
    if name in ("Edit", "MultiEdit", "Write", "NotebookEdit"):
        return (f"Editing {base}" if base else "Editing"), base
    if name == "Read":
        return (f"Reading {base}" if base else "Reading"), base
    if name in ("Grep", "Glob"):
        return "Searching", _short(args.get("pattern"))
    if name in ("WebFetch", "WebSearch"):
        return "Browsing", _short(args.get("url") or args.get("query"))
    if name in ("TodoWrite", "TaskCreate", "TaskUpdate"):
        return "Planning", ""
    if name in ("Task", "Agent"):
        return "Delegating", _short(args.get("description"))
    return name, ""


def _apply_todos(s: dict[str, Any], todos: Any) -> None:
    if not isinstance(todos, list):
        return
    items = [t for t in todos if isinstance(t, dict)]
    s["total"] = len(items)
    s["done"] = sum(1 for t in items if t.get("status") == "completed")
    current = next((t for t in items if t.get("status") == "in_progress"), None)
    s["task"] = _short(current.get("activeForm") or current.get("content")) if current else ""


def apply_claude(state: dict[str, Any], event: dict[str, Any], now: float) -> None:
    """Update the state from one Claude Code hook event (the JSON Claude Code writes to the hook's stdin)."""
    sid = event.get("session_id")
    if not isinstance(sid, str) or not sid:
        return
    name = event.get("hook_event_name")
    if name == "SessionEnd":
        state["sessions"].pop(f"claude:{sid}", None)
        return
    s = _session(state, "claude", sid, event.get("cwd"), now)
    if name == "UserPromptSubmit":
        s.update(state="running", task="", activity="Thinking", detail="", done=0, total=0, started=now)
    elif name in ("PreToolUse", "PostToolUse"):
        tool = event.get("tool_name") or ""
        args = event.get("tool_input") if isinstance(event.get("tool_input"), dict) else {}
        s["state"] = "running"
        s["activity"], s["detail"] = _describe_tool(tool, args)
        if name == "PreToolUse" and tool in _ASKS_YOU:
            s["state"], s["activity"], s["detail"] = "waiting", _ASKS_YOU[tool], ""
        if "todos" in args:
            _apply_todos(s, args["todos"])
    elif name == "PermissionRequest":
        tool = event.get("tool_name") or ""
        args = event.get("tool_input") if isinstance(event.get("tool_input"), dict) else {}
        s["state"] = "waiting"
        s["activity"], s["detail"] = _describe_tool(tool, args)
    elif name == "Notification":
        kind = event.get("notification_type")
        message = event.get("message") if isinstance(event.get("message"), str) else ""
        # An idle reminder after a finished turn isn't a question: leave the session "done".
        idle = kind == "idle_prompt" or "waiting for your input" in message
        if not (idle and s["state"] in ("done", "idle")):
            s["state"] = "waiting"
            s["activity"] = _short(message) or "Needs you"
    elif name == "Stop":
        _finish(state, s, now)
        s["activity"] = "Finished"


# -- Codex ----------------------------------------------------------------------------------


def apply_codex(state: dict[str, Any], payload: dict[str, Any], now: float) -> None:
    """Update the state from Codex's `notify` program argument (JSON with a "type", e.g. agent-turn-complete)."""
    kind = payload.get("type")
    if not isinstance(kind, str):
        return
    sid = payload.get("thread-id") or payload.get("session-id") or payload.get("cwd") or "codex"
    s = _session(state, "codex", str(sid), payload.get("cwd"), now)
    if kind == "agent-turn-complete":
        _finish(state, s, now)
        s["activity"] = "Finished"  # never the answer itself: it would leave the computer
    elif "approval" in kind:
        s["state"] = "waiting"
        s["activity"] = "Needs approval"
        command = payload.get("command")
        s["detail"] = _short(" ".join(command) if isinstance(command, list) else command)
    else:
        s["state"] = "running"
        s["activity"] = "Working"


# -- what the watch reads ---------------------------------------------------------------------


def _midnight(now: float) -> float:
    local = dt.datetime.fromtimestamp(now).astimezone()
    return local.replace(hour=0, minute=0, second=0, microsecond=0).timestamp()


def summary(state: dict[str, Any], now: float) -> dict[str, Any]:
    """The JSON the watch fetches (schema v1, documented in docs/agent-watch-face.md)."""
    agents = []
    for s in state["sessions"].values():
        age = now - s.get("updated", 0)
        if s.get("state") == "idle" or age >= KEEP_SECONDS or (s.get("state") == "done" and age >= SHOW_DONE_SECONDS):
            continue
        a = {k: s.get(k, d) for k, d in (
            ("agent", ""), ("project", ""), ("state", "idle"), ("task", ""), ("activity", ""), ("detail", ""),
            ("done", 0), ("total", 0), ("started", 0), ("updated", 0))}
        a["started"], a["updated"] = round(a["started"]), round(a["updated"])
        agents.append(a)
    agents.sort(key=lambda a: (_STATE_ORDER.get(a["state"], 9), -a["updated"]))
    since = _midnight(now)
    return {
        "v": SCHEMA_VERSION,
        "now": round(now),
        "agents": agents,
        "running": sum(1 for a in agents if a["state"] == "running"),
        "waiting": sum(1 for a in agents if a["state"] == "waiting"),
        "doneToday": sum(1 for t in state["finished"] if t >= since),
    }


def record(apply, event: dict[str, Any], now: float | None = None) -> None:
    now = time.time() if now is None else now
    with _locked_state() as state:
        apply(state, event, now)
        _prune(state, now)


def run_hook(agent: str, raw: str) -> int:
    """Entry point for the hook commands. Always succeeds: a broken status file must never stop an agent."""
    try:
        event = json.loads(raw) if raw.strip() else {}
        if isinstance(event, dict):
            record(apply_claude if agent == "claude" else apply_codex, event)
    except Exception as e:
        print(f"gillspeak agents: {e}", file=sys.stderr)
    return 0


# -- the token and the server -----------------------------------------------------------------


def normalize_token(token: str) -> str:
    return "".join(ch for ch in token.lower() if ch.isalnum())


def format_token(token: str) -> str:
    return "-".join(token[i:i + 4] for i in range(0, len(token), 4))


def get_token(*, renew: bool = False) -> str:
    """The token the watch sends, created on first use. Stored 0600 in the config folder, never in the repo."""
    path = token_file()
    if not renew:
        try:
            token = normalize_token(path.read_text(encoding="utf-8"))
            if len(token) >= TOKEN_LENGTH:
                return token
        except OSError:
            pass
    token = "".join(secrets.choice(TOKEN_ALPHABET) for _ in range(TOKEN_LENGTH))
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(token + "\n")
    os.chmod(path, 0o600)
    return token


def lan_address() -> str:
    """This computer's address on the local network (no packet is sent: a UDP connect only picks a route)."""
    with contextlib.suppress(OSError), socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("192.0.2.1", 9))  # TEST-NET-1: never routed anywhere real
        return s.getsockname()[0]
    return "127.0.0.1"


def make_handler(token_source=get_token):
    """Checks every request against the token on disk, so `gillspeak agents token --new` locks the old one out at once."""
    from http.server import BaseHTTPRequestHandler  # not at the top: the hooks never need it

    class Handler(BaseHTTPRequestHandler):
        server_version = "gillspeak-agents"
        sys_version = ""

        def do_GET(self) -> None:
            if self.path.split("?")[0] != "/v1/agents":
                self._send(404, {"error": "not found"})
                return
            given = self.headers.get("Authorization", "")
            given = "Bearer " + normalize_token(given[7:]) if given.startswith("Bearer ") else given
            if not hmac.compare_digest(given.encode(), f"Bearer {token_source()}".encode()):
                self._send(401, {"error": "wrong or missing token"})
                return
            self._send(200, summary(load_state(), time.time()))

        def _send(self, code: int, body: dict[str, Any]) -> None:
            data = json.dumps(body, separators=(",", ":")).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, format: str, *args: Any) -> None:  # requests aren't logged: they carry the token
            pass

    return Handler


def serve(host: str, port: int) -> int:
    from http.server import ThreadingHTTPServer

    get_token()  # create it now, so `gillspeak agents token` shows the one the server checks
    server = ThreadingHTTPServer((host, port), make_handler())
    address = lan_address() if host in ("", "0.0.0.0") else host
    print(f"Serving agent status on http://{address}:{server.server_port}  (Ctrl+C to stop)")
    print("On the watch: open gillspeak agents and enter this address under Computer, with the token from `gillspeak agents token`.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


def print_status() -> int:
    now = time.time()
    info = summary(load_state(), now)
    if not info["agents"]:
        print("No agent sessions in the last 2 hours.")
    for a in info["agents"]:
        steps = f" {a['done']}/{a['total']}" if a["total"] else ""
        what = a["task"] or a["activity"]
        print(f"{a['agent']:<7}{a['project'] or '?':<20}{a['state']:<9}{steps:<7}{what}")
    print(f"{info['running']} running, {info['waiting']} waiting, {info['doneToday']} finished today")
    return 0


CLAUDE_EVENTS = ["SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "PermissionRequest",
                 "Notification", "Stop", "SessionEnd"]


def hook_config(command: str = "gillspeak") -> str:
    """The settings to paste into Claude Code and Codex."""
    hook = [{"hooks": [{"type": "command", "command": f"{command} agents claude-hook"}]}]
    tool_hook = [{"matcher": "*", **hook[0]}]
    claude = {"hooks": {e: tool_hook if e.endswith("ToolUse") or e == "PermissionRequest" else hook for e in CLAUDE_EVENTS}}
    return (
        "# Claude Code: merge into ~/.claude/settings.json\n"
        + json.dumps(claude, indent=2)
        + "\n\n# Codex: add to ~/.codex/config.toml\n"
        + f'notify = ["{command}", "agents", "codex-notify"]\n'
    )
