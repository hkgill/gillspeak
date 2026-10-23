import json
import os
import stat
import subprocess
import sys
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

from gillspeak import agents, cli

NOW = 1_800_000_000.0


def claude(state, name, now=NOW, sid="s1", **fields):
    agents.apply_claude(state, {"session_id": sid, "hook_event_name": name, "cwd": "/home/me/gillspeak", **fields}, now)


def only(state):
    (s,) = state["sessions"].values()
    return s


def test_claude_session_runs_through_a_plan():
    st = agents._empty()
    claude(st, "SessionStart")
    assert only(st)["state"] == "idle"
    claude(st, "UserPromptSubmit", prompt="fix the tests")
    assert only(st)["state"] == "running"
    todos = [
        {"content": "Read the code", "status": "completed", "activeForm": "Reading the code"},
        {"content": "Run tests", "status": "in_progress", "activeForm": "Running tests"},
        {"content": "Fix", "status": "pending", "activeForm": "Fixing"},
    ]
    claude(st, "PostToolUse", tool_name="TodoWrite", tool_input={"todos": todos})
    s = only(st)
    assert (s["done"], s["total"], s["task"], s["project"], s["activity"]) == (1, 3, "Running tests", "gillspeak", "Planning")
    claude(st, "PreToolUse", tool_name="Bash", tool_input={"command": "./gradlew test", "description": "Run the unit tests"})
    assert (s["activity"], s["detail"]) == ("Run the unit tests", "./gradlew test")
    claude(st, "Stop", now=NOW + 60)
    assert s["state"] == "done"
    assert st["finished"] == [NOW + 60]


def test_permission_notification_waits_and_idle_reminder_does_not():
    st = agents._empty()
    claude(st, "UserPromptSubmit")
    claude(st, "PreToolUse", tool_name="Bash", tool_input={"command": "rm -rf build"})
    claude(st, "Notification", message="Claude needs your permission to use Bash", notification_type="permission_prompt")
    s = only(st)
    assert (s["state"], s["detail"]) == ("waiting", "rm -rf build")
    claude(st, "PostToolUse", tool_name="Bash", tool_input={"command": "rm -rf build"})
    assert s["state"] == "running"
    claude(st, "Stop")
    claude(st, "Notification", message="Claude is waiting for your input", notification_type="idle_prompt")
    assert s["state"] == "done"


def test_questions_and_plans_wait_for_you():
    st = agents._empty()
    claude(st, "UserPromptSubmit")
    claude(st, "PreToolUse", tool_name="AskUserQuestion", tool_input={"questions": []})
    s = only(st)
    assert (s["state"], s["activity"]) == ("waiting", "Asking you a question")
    claude(st, "PostToolUse", tool_name="AskUserQuestion", tool_input={"questions": []})
    assert s["state"] == "running"
    claude(st, "PreToolUse", tool_name="ExitPlanMode", tool_input={"plan": "..."})
    assert (s["state"], s["activity"]) == ("waiting", "Plan ready for review")


def test_hook_import_is_light():
    code = "import sys, gillspeak.agents; print('http.server' in sys.modules)"
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True).stdout.strip()
    assert out == "False"


def test_stop_counts_once_and_session_end_removes():
    st = agents._empty()
    claude(st, "UserPromptSubmit")
    claude(st, "Stop")
    claude(st, "Stop")
    assert len(st["finished"]) == 1
    claude(st, "SessionEnd", reason="exit")
    assert st["sessions"] == {}


def test_bad_events_are_ignored():
    st = agents._empty()
    agents.apply_claude(st, {"hook_event_name": "Stop"}, NOW)  # no session id
    agents.apply_codex(st, {"no": "type"}, NOW)
    assert st["sessions"] == {}
    claude(st, "PreToolUse", tool_name="Bash", tool_input="not a dict")
    assert only(st)["activity"] == "Running a command"


def test_codex_turns_and_approvals():
    st = agents._empty()
    agents.apply_codex(st, {"type": "agent-turn-complete", "thread-id": "t1", "cwd": "/src/app",
                            "last-assistant-message": "Added the watch face.\nAll tests pass."}, NOW)
    s = only(st)
    assert (s["agent"], s["project"], s["state"], s["activity"]) == ("codex", "app", "done", "Finished")
    assert "watch face" not in json.dumps(agents.summary(st, NOW))  # the answer never leaves the computer
    agents.apply_codex(st, {"type": "approval-requested", "thread-id": "t1", "command": ["npm", "test"]}, NOW)
    assert (s["state"], s["detail"]) == ("waiting", "npm test")


def test_summary_orders_and_hides_old_and_idle():
    st = agents._empty()
    claude(st, "UserPromptSubmit", sid="running", now=NOW - 10)
    claude(st, "UserPromptSubmit", sid="waiting", now=NOW - 20)
    claude(st, "Notification", sid="waiting", now=NOW - 20, message="Claude needs your permission to use Bash")
    claude(st, "UserPromptSubmit", sid="old", now=NOW - agents.SHOW_DONE_SECONDS - 5)
    claude(st, "Stop", sid="old", now=NOW - agents.SHOW_DONE_SECONDS - 5)
    claude(st, "SessionStart", sid="idle")
    out = agents.summary(st, NOW)
    assert [a["state"] for a in out["agents"]] == ["waiting", "running"]
    assert (out["running"], out["waiting"]) == (1, 1)
    assert "id" not in out["agents"][0]
    assert out["v"] == 1


def test_done_today_counts_since_local_midnight():
    st = agents._empty()
    midnight = agents._midnight(NOW)
    st["finished"] = [midnight - 5, midnight + 5, NOW]
    assert agents.summary(st, NOW)["doneToday"] == 2


def test_prune_drops_silent_sessions():
    st = agents._empty()
    claude(st, "UserPromptSubmit", now=NOW - agents.KEEP_SECONDS - 1)
    agents._prune(st, NOW)
    assert st["sessions"] == {}


def test_long_text_is_shortened():
    assert agents._short("a  b\n c") == "a b c"
    long = agents._short("x" * 200)
    assert len(long) == agents.TEXT_LIMIT and long.endswith("…")


def test_hook_writes_the_state_file_and_never_fails(capsys):
    event = {"session_id": "s1", "hook_event_name": "UserPromptSubmit", "cwd": "/w/proj"}
    assert agents.run_hook("claude", json.dumps(event)) == 0
    assert agents.load_state()["sessions"]["claude:s1"]["state"] == "running"
    assert stat.S_IMODE(os.stat(agents.state_file()).st_mode) == 0o600
    assert agents.run_hook("claude", "{not json") == 0
    assert "gillspeak agents" in capsys.readouterr().err
    assert agents.run_hook("codex", "") == 0


def test_corrupt_state_file_starts_over():
    agents.state_file().parent.mkdir(parents=True)
    agents.state_file().write_text("[1, 2]")
    assert agents.load_state() == agents._empty()


def test_token_is_created_once_private_and_typeable():
    token = agents.get_token()
    assert len(token) == agents.TOKEN_LENGTH and set(token) <= set(agents.TOKEN_ALPHABET)
    assert agents.get_token() == token
    assert stat.S_IMODE(os.stat(agents.token_file()).st_mode) == 0o600
    assert agents.get_token(renew=True) != token
    assert agents.normalize_token(agents.format_token(token).upper()) == token


@pytest.fixture
def server():
    token = agents.get_token()
    srv = ThreadingHTTPServer(("127.0.0.1", 0), agents.make_handler())
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_port}", token
    srv.shutdown()
    srv.server_close()


def get(url, token=None):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"} if token else {})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def test_server_needs_the_token(server):
    base, token = server
    agents.run_hook("claude", json.dumps({"session_id": "s1", "hook_event_name": "UserPromptSubmit", "cwd": "/w/proj"}))
    assert get(base + "/v1/agents")[0] == 401
    assert get(base + "/v1/agents", "wrong-token-x")[0] == 401
    code, body = get(base + "/v1/agents", agents.format_token(token).upper())  # as typed on the watch
    assert code == 200
    assert body["agents"][0]["project"] == "proj"
    assert get(base + "/other", token)[0] == 404


def test_new_token_locks_the_old_one_out_without_a_restart(server):
    base, old = server
    new = agents.get_token(renew=True)
    assert get(base + "/v1/agents", old)[0] == 401
    assert get(base + "/v1/agents", new)[0] == 200


def test_cli_agents_commands(capsys):
    assert cli.build_parser().parse_args(["agents"]).action == "status"
    with pytest.raises(SystemExit) as e:
        cli.main(["agents", "codex-notify", json.dumps({"type": "agent-turn-complete", "cwd": "/w/app"})])
    assert e.value.code == 0
    with pytest.raises(SystemExit):
        cli.main(["agents"])
    out = capsys.readouterr().out
    assert "codex" in out and "1 finished today" in out
    with pytest.raises(SystemExit):
        cli.main(["agents", "hooks"])
    out = capsys.readouterr().out
    assert '"command": "gillspeak agents claude-hook"' in out
    assert 'notify = ["gillspeak", "agents", "codex-notify"]' in out
    settings = json.loads(out.split("\n", 1)[1].split("\n\n#")[0])
    assert set(settings["hooks"]) == set(agents.CLAUDE_EVENTS)
