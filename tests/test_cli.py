import subprocess
import sys

import pytest

from gillspeak import cli


def test_cli_import_is_light():
    code = "import sys, gillspeak.cli; bad = [m for m in ('numpy', 'sherpa_onnx', 'httpx', 'sounddevice') if m in sys.modules]; print(bad)"
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True).stdout.strip()
    assert out == "[]"


def test_not_running_exit_code(capsys):
    with pytest.raises(SystemExit) as e:
        cli.main(["toggle"])
    assert e.value.code == 2
    assert "gillspeakd is not running — systemctl --user start gillspeakd" in capsys.readouterr().err


def test_parser():
    a = cli.build_parser().parse_args(["toggle", "--mode", "formal", "--chord", "ctrl+shift+v"])
    assert (a.mode, a.raw, a.chord) == ("formal", False, "ctrl+shift+v")
    a = cli.build_parser().parse_args(["toggle", "--raw"])
    assert a.raw is True


# -- against a fake daemon on the (isolated) socket -----------------------------------

import json  # noqa: E402
import socket  # noqa: E402
import threading  # noqa: E402

from gillspeak import paths  # noqa: E402


@pytest.fixture
def fake_daemon():
    """Answer one request per connection with `reply`; record requests."""
    state = {"reply": {"ok": True, "state": "idle", "msg": ""}, "requests": []}
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(str(paths.socket_path()))
    srv.listen(4)

    def serve():
        while True:
            try:
                conn, _ = srv.accept()
            except OSError:
                return
            with conn:
                line = conn.makefile().readline()
                state["requests"].append(json.loads(line))
                conn.sendall((json.dumps(state["reply"]) + "\n").encode())

    threading.Thread(target=serve, daemon=True).start()
    yield state
    srv.close()


def run(argv):
    with pytest.raises(SystemExit) as e:
        cli.main(argv)
    return e.value.code


def test_toggle_sends_options(fake_daemon):
    assert run(["toggle", "--mode", "formal", "--chord", "ctrl+shift+v"]) == 0
    assert fake_daemon["requests"] == [{"cmd": "toggle", "mode": "formal", "raw": False, "chord": "ctrl+shift+v"}]


@pytest.mark.parametrize("argv,cmd", [(["cancel"], "cancel"), (["paste-last"], "paste_last"), (["reload"], "reload")])
def test_simple_commands(fake_daemon, argv, cmd):
    assert run(argv) == 0
    assert fake_daemon["requests"][-1] == {"cmd": cmd}


def test_status_prints_state_and_message(fake_daemon, capsys):
    fake_daemon["reply"] = {"ok": True, "state": "recording", "msg": "hello"}
    assert run(["status"]) == 0
    assert capsys.readouterr().out.strip() == "recording — hello"


def test_daemon_refusal_is_exit_1(fake_daemon, capsys):
    fake_daemon["reply"] = {"ok": False, "state": "idle", "msg": "busy: two dictations already processing"}
    assert run(["toggle"]) == 1
    assert "busy" in capsys.readouterr().err


def test_bad_config_is_reported_not_a_traceback(capsys):
    paths.config_dir().mkdir(parents=True)
    paths.config_file().write_text("[inject]\nmethod = 'telepathy'\n")
    assert run(["stats"]) == 1
    assert capsys.readouterr().err.startswith("config error:")


def test_history_and_stats_on_empty_db(capsys):
    assert run(["history", "-n", "5"]) == 0
    assert run(["stats", "--days", "7"]) == 0
    out = capsys.readouterr().out
    assert "Last 7 days" in out and "dictations        0" in out


def test_set_key_empty_input(monkeypatch, capsys):
    import io

    monkeypatch.setattr(sys, "stdin", io.StringIO("\n"))
    assert run(["set-key"]) == 1
    assert "empty key" in capsys.readouterr().err


def test_download_unknown_engine(capsys):
    assert run(["download-models", "--engine", "nope"]) == 1
    assert "unknown engine 'nope'" in capsys.readouterr().err


def test_eval_default_set_ships_inside_the_package():
    """Regression: the default eval set pointed into the source tree, so installed copies couldn't find it."""
    from pathlib import Path

    from gillspeak import evaluate

    assert evaluate.DEFAULT_SET.is_file()
    assert Path(evaluate.__file__).parent in evaluate.DEFAULT_SET.parents
    a = cli.build_parser().parse_args(["eval"])
    assert a.file is None and a.delay == 4.0
