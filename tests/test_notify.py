"""Notifications replace each other in place; errors persist; sounds are optional. Subprocesses are faked."""

import asyncio

import pytest

from gillspeak import notify
from gillspeak.notify import Notifier, sound_path


class FakeProc:
    def __init__(self, out=b"", fail=False):
        self.out, self.fail = out, fail

    async def communicate(self):
        return self.out, b""

    async def wait(self):
        return 0


@pytest.fixture
def calls(monkeypatch):
    seen = []
    ids = iter(range(41, 100))

    async def fake_exec(*argv, **kw):
        seen.append(list(argv))
        if argv[0] == "notify-send":
            return FakeProc(f"{next(ids)}\n".encode())
        return FakeProc()

    monkeypatch.setattr(notify.shutil, "which", lambda tool: f"/usr/bin/{tool}")
    monkeypatch.setattr(notify.asyncio, "create_subprocess_exec", fake_exec)
    return seen


def test_bundled_sounds_exist():
    for name in ("start", "stop", "error"):
        assert sound_path(name).is_file()


async def test_listening_then_processing_replaces_in_place(calls):
    n = Notifier()
    await n.listening()
    await n.processing()
    await asyncio.sleep(0)  # let the sound tasks run
    shows = [c for c in calls if c[0] == "notify-send"]
    assert shows[0][-1] == "🎙 Listening…" and "--replace-id" not in shows[0]
    assert shows[1][-1] == "⏳ Processing…" and shows[1][shows[1].index("--replace-id") + 1] == "41"
    assert [c[0] for c in calls if c[0] == "pw-play"] == ["pw-play", "pw-play"]


async def test_mode_tags(calls):
    n = Notifier()
    await n.listening("formal")
    await n.listening(raw=True)
    titles = [c[-1] for c in calls if c[0] == "notify-send"]
    assert titles == ["🎙 Listening… (formal)", "🎙 Listening… (raw)"]


async def test_error_is_persistent_and_not_replaced(calls):
    n = Notifier()
    await n.listening()
    await n.error("No microphone", "Check Settings")
    await n.listening()
    shows = [c for c in calls if c[0] == "notify-send"]
    err = shows[1]
    assert "critical" in err and "boolean:transient:true" not in err and err[-2:] == ["⚠ No microphone", "Check Settings"]
    assert "--replace-id" not in shows[2]  # the error stays on screen


async def test_clear_closes_the_current_notification(calls):
    n = Notifier()
    await n.listening()
    await n.clear()
    close = [c for c in calls if c[0] == "gdbus"]
    assert close and close[0][-1] == "41"
    await n.clear()  # nothing open: no second call
    assert len([c for c in calls if c[0] == "gdbus"]) == 1


async def test_disabled_or_missing_tools_do_nothing(monkeypatch, calls):
    n = Notifier(notifications=False, sounds=False)
    await n.listening()
    await n.error("x")
    await n.clear()
    assert calls == []
    monkeypatch.setattr(notify.shutil, "which", lambda tool: None)
    n = Notifier()
    assert not n.notifications and not n.sounds


async def test_notify_send_failure_is_swallowed(monkeypatch):
    async def broken(*argv, **kw):
        raise OSError("gone")

    monkeypatch.setattr(notify.shutil, "which", lambda tool: "/usr/bin/x")
    monkeypatch.setattr(notify.asyncio, "create_subprocess_exec", broken)
    n = Notifier()
    await n.info("hello")  # no exception
    assert n._id is None
