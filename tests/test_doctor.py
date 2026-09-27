"""`murmur doctor` checks, with every external tool, device and network call faked."""

import os
import socket
import subprocess
from pathlib import Path

import pytest

from murmur import doctor


def lines(capsys):
    return capsys.readouterr().out.splitlines()


def test_report_counts_only_failures(capsys):
    r = doctor.Report()
    r.line(doctor.OK, "a", hint="never shown for OK")
    r.line(doctor.WARN, "b", "detail", "fix b")
    r.line(doctor.FAIL, "c", hint="fix c")
    assert r.failures == 1
    out = lines(capsys)
    assert out == ["✅ a", "⚠️  b: detail", "     → fix b", "❌ c", "     → fix c"]


def test_check_turns_exceptions_into_failures(capsys):
    r = doctor.Report()
    assert not r.check("boom", lambda: 1 / 0, "hint")
    assert r.failures == 1
    assert "ZeroDivisionError" in capsys.readouterr().out


def test_run_with_stdin_does_not_hold_pipes(monkeypatch):
    """Regression: wl-copy forks a clipboard server that keeps inherited pipes open, so capturing
    its output would block until the timeout. Commands fed stdin must not capture output."""
    seen = {}

    def fake_run(argv, **kw):
        seen.update(kw)
        return subprocess.CompletedProcess(argv, 0)

    monkeypatch.setattr(doctor.subprocess, "run", fake_run)
    doctor._run(["wl-copy"], stdin=b"x")
    assert seen["stdout"] is subprocess.DEVNULL and seen["stderr"] is subprocess.DEVNULL
    assert "capture_output" not in seen


# -- hold-to-talk helper -----------------------------------------------------------


@pytest.fixture
def listening(tmp_path):
    path = tmp_path / "keyd.sock"
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.bind(str(path))
    s.listen(1)
    yield path
    s.close()


def test_keyd_not_running_is_a_warning_not_a_failure(tmp_path, capsys):
    r = doctor.Report()
    doctor.check_keyd(r, str(tmp_path / "missing.sock"))
    assert r.failures == 0
    out = capsys.readouterr().out
    assert "⚠️  hold-to-talk (murmur-keyd): not reachable" in out and "install-keyd.sh" in out


def test_keyd_matching_copy(listening, tmp_path, capsys):
    installed = tmp_path / "murmur-keyd"
    installed.write_bytes(Path(doctor.__file__).with_name("keyd.py").read_bytes())
    r = doctor.Report()
    doctor.check_keyd(r, str(listening), installed)
    out = capsys.readouterr().out
    assert "✅ hold-to-talk (murmur-keyd): listening" in out and "✅ murmur-keyd version: matches" in out


def test_keyd_stale_copy_is_flagged(listening, tmp_path, capsys):
    """Regression: an old root copy kept running after keyd.py was fixed, and nothing said so."""
    installed = tmp_path / "murmur-keyd"
    installed.write_text("# an older murmur-keyd\n")
    r = doctor.Report()
    doctor.check_keyd(r, str(listening), installed)
    out = capsys.readouterr().out
    assert "⚠️  murmur-keyd version" in out and "differs" in out and "install-keyd.sh" in out


def test_keyd_unreadable_copy(listening, tmp_path, capsys):
    r = doctor.Report()
    doctor.check_keyd(r, str(listening), tmp_path / "nope")
    assert "cannot read" in capsys.readouterr().out and r.failures == 0


# -- input device permissions -------------------------------------------------------


def _devices(tmp_path, modes):
    d = tmp_path / "input"
    d.mkdir()
    for name, mode in modes.items():
        (d / name).touch()
        os.chmod(d / name, mode)
    return d


def test_world_readable_keyboards_are_flagged(tmp_path, capsys):
    """Regression: another dictation app left /dev/input/event* at 0666 (any process could keylog)."""
    d = _devices(tmp_path, {"event0": 0o666, "event3": 0o660, "mouse0": 0o666})
    doctor.check_input_permissions(doctor.Report(), d)
    out = capsys.readouterr().out
    assert "⚠️  input device permissions: event0 readable by all users" in out
    assert "event3" not in out.splitlines()[0] and "mouse0" not in out


def test_many_exposed_devices_are_truncated(tmp_path, capsys):
    d = _devices(tmp_path, {f"event{i}": 0o664 for i in range(6)})
    doctor.check_input_permissions(doctor.Report(), d)
    assert "…" in capsys.readouterr().out


def test_private_keyboards_pass(tmp_path, capsys):
    d = _devices(tmp_path, {"event0": 0o660, "event3": 0o600})
    doctor.check_input_permissions(doctor.Report(), d)
    assert "✅ input device permissions" in capsys.readouterr().out


def test_missing_input_dir_is_silent(tmp_path, capsys):
    doctor.check_input_permissions(doctor.Report(), tmp_path / "nope")
    assert capsys.readouterr().out == ""


# -- whole run, everything external faked ----------------------------------------------


def test_run_doctor_offline(monkeypatch, capsys, tmp_path):
    from murmur import paths

    paths.config_dir().mkdir(parents=True)
    paths.config_file().write_text(f'[asr]\nmodel_dir = "{tmp_path / "models"}"\n')
    monkeypatch.setenv("XDG_SESSION_TYPE", "x11")
    monkeypatch.setattr(doctor.shutil, "which", lambda tool: None)
    monkeypatch.setattr(doctor, "INPUT_DIR", tmp_path / "none")
    rc = doctor.run_doctor(skip_llm=True, skip_mic=True)
    out = capsys.readouterr().out
    assert rc == 1  # tools and models missing
    assert "❌ xclip installed" in out and "sudo dnf install xclip" in out
    assert "⚠️  murmurd running: not running" in out
    assert "❌ model parakeet-tdt-0.6b-v3-int8" in out and "murmur download-models" in out
    assert "problem(s) found" in out


def test_run_doctor_bad_config(monkeypatch, capsys):
    from murmur import paths

    paths.config_dir().mkdir(parents=True)
    paths.config_file().write_text("[llm]\nprovider = 'carrier-pigeon'\n")
    assert doctor.run_doctor(skip_llm=True, skip_mic=True) == 1
    assert "❌ config" in capsys.readouterr().out
