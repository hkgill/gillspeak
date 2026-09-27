"""Daemon pipeline and CLI <-> daemon round-trips, with every external dependency faked."""

import asyncio
import os
import stat

import numpy as np
import pytest

from gillspeak import cli, paths
from gillspeak.cleaner import CleanerError, CleanResult
from gillspeak.config import Config, Dictionary
from gillspeak.daemon import Daemon
from gillspeak.history import History
from gillspeak.inject import InjectError

LONG_RAW = "um so the the meeting with super base folks moved to thursday sorry friday so can you send the deck before then"


class FakeRecorder:
    def __init__(self):
        self.is_recording = False
        self.audio = np.ones(16000 * 2, dtype=np.float32)

    def start(self):
        self.is_recording = True

    def stop(self):
        self.is_recording = False
        return self.audio

    def cancel(self):
        self.is_recording = False

    def close(self):
        pass


class AllSpeech:
    def segments(self, audio):
        return [(0, len(audio))] if len(audio) else []


class FakeTranscriber:
    def __init__(self, text):
        self.text = text

    def transcribe(self, audio, sample_rate=16000):
        return self.text


class FakeCleaner:
    model = "fake-lite"

    def __init__(self, result=None, error=None):
        self.result, self.error, self.calls = result, error, []

    async def clean(self, text, *, mode, instructions, bias_terms):
        self.calls.append((text, mode, bias_terms))
        if self.error:
            raise self.error
        return CleanResult(self.result, 300, 20, 400)

    def needs_warm(self):
        return False

    async def warm(self):
        pass

    async def aclose(self):
        pass


class FakeInjector:
    def __init__(self, error=None):
        self.inserted, self.error = [], error

    async def insert(self, text, chord=None):
        if self.error:
            raise self.error
        self.inserted.append((text, chord))


class FakeNotifier:
    def __init__(self):
        self.events = []
        self.notifications = self.sounds = True

    def __getattr__(self, name):
        async def record(*args, **kw):
            self.events.append((name, args))

        return record


def cloud_config() -> Config:
    """These tests exercise the opt-in cloud clean-up path; the shipped default is local only."""
    cfg = Config()
    cfg.llm.provider = "gemini"
    return cfg


@pytest.fixture
def make_daemon(tmp_path):
    created = []

    def factory(raw=LONG_RAW, cleaner=None, injector=None, cfg=None, dictionary=None):
        d = Daemon(
            cfg or cloud_config(),
            dictionary or Dictionary(replace={"super base": "Supabase"}, bias=["Supabase"]),
            recorder=FakeRecorder(),
            transcriber=FakeTranscriber(raw),
            vad=AllSpeech(),
            cleaner=cleaner if cleaner is not None else FakeCleaner("The meeting with Supabase folks moved to Friday. Can you send the deck before then?"),
            injector=injector or FakeInjector(),
            history=History(tmp_path / "h.db"),
            notifier=FakeNotifier(),
        )
        d.ready.set()
        created.append(d)
        return d

    yield factory


async def dictate(d, **req):
    assert (await d.handle({"cmd": "toggle", **req}))["state"] == "recording"
    resp = await d.handle({"cmd": "toggle"})
    assert resp["ok"] and resp["msg"] == "processing"
    await d.process(d.queue.get_nowait())
    d.inflight -= 1
    return d.history.recent(1)[0] if d.history.recent(1) else None


async def test_llm_path(make_daemon):
    d = make_daemon()
    row = await dictate(d)
    assert d.injector.inserted == [("The meeting with Supabase folks moved to Friday. Can you send the deck before then?", None)]
    text, mode, bias = d.cleaner.calls[0]
    assert text.startswith("So the meeting with Supabase folks")  # rules ran first
    assert (mode, bias) == ("default", ["Supabase"])
    assert row["llm_status"] == "ok" and row["gate_llm"] == 1 and row["prompt_ver"] == "clean_v2"
    assert row["input_tokens"] == 300 and row["llm_model"] == "fake-lite"
    assert row["inject_status"] == "pasted"
    assert row["raw_text"] == LONG_RAW
    assert d.state == "idle"


async def test_dictionary_reapplied_after_llm(make_daemon):
    d = make_daemon(cleaner=FakeCleaner("The meeting with super base folks moved to Friday. Can you send the deck before then?"))
    await dictate(d)
    assert "Supabase" in d.injector.inserted[0][0]


async def test_short_text_skips_llm(make_daemon):
    d = make_daemon(raw="Sounds good.")
    row = await dictate(d)
    assert d.cleaner.calls == []
    assert d.injector.inserted[0][0] == "Sounds good."
    assert row["llm_status"] == "skipped" and row["gate_reason"].startswith("short")


async def test_raw_mode_skips_llm(make_daemon):
    d = make_daemon()
    row = await dictate(d, raw=True)
    assert d.cleaner.calls == []
    assert row["gate_reason"] == "raw"


async def test_mode_and_chord_from_start_request(make_daemon):
    d = make_daemon(raw="Hi.")
    await dictate(d, mode="formal", chord="ctrl+shift+v")
    assert d.cleaner.calls[0][1] == "formal"
    assert d.injector.inserted[0][1] == "ctrl+shift+v"


@pytest.mark.parametrize("kind", ["timeout", "offline", "error", "auth", "blocked"])
async def test_llm_failure_pastes_rules_text(make_daemon, kind):
    d = make_daemon(cleaner=FakeCleaner(error=CleanerError(kind)))
    row = await dictate(d)
    assert d.injector.inserted[0][0].startswith("So the meeting with Supabase folks moved to thursday sorry friday")
    assert row["llm_status"] == kind


async def test_rate_limit_backs_off(make_daemon):
    d = make_daemon(cleaner=FakeCleaner(error=CleanerError("rate_limited")))
    await dictate(d)
    assert d.backoff_until > 0
    row = await dictate(d)
    assert row["gate_reason"] == "rate_limit_backoff"
    assert len(d.cleaner.calls) == 1


async def test_validator_rejection(make_daemon):
    d = make_daemon(cleaner=FakeCleaner("Sure! Here is the cleaned text: meeting Friday."))
    row = await dictate(d)
    assert row["llm_status"].startswith("rejected:")
    assert d.injector.inserted[0][0].startswith("So the meeting")


async def test_no_speech_discarded(make_daemon):
    d = make_daemon()
    d.recorder.audio = np.zeros(0, dtype=np.float32)
    row = await dictate(d)
    assert row is None and d.injector.inserted == []


async def test_ydotool_missing_keeps_clipboard(make_daemon):
    d = make_daemon(raw="Hi there.", injector=FakeInjector(InjectError("no socket", copied=True, kind="ydotool")))
    row = await dictate(d)
    assert row["inject_status"] == "copied"
    assert any(e[0] == "error" and "Copied" in e[1][0] for e in d.notifier.events)
    assert d.last_text == "Hi there."


async def test_cancel(make_daemon):
    d = make_daemon()
    await d.handle({"cmd": "toggle"})
    resp = await d.handle({"cmd": "cancel"})
    assert resp == {"ok": True, "state": "idle", "msg": "cancelled"}
    assert d.queue.empty()


async def test_paste_last(make_daemon):
    d = make_daemon(raw="Hello.")
    assert (await d.handle({"cmd": "paste_last"}))["ok"] is False
    await dictate(d)
    resp = await d.handle({"cmd": "paste_last"})
    assert resp["ok"] and d.injector.inserted[-1] == ("Hello.", None)


async def test_busy_when_two_jobs_in_flight(make_daemon):
    d = make_daemon()
    for _ in range(2):
        await d.handle({"cmd": "toggle"})
        await d.handle({"cmd": "toggle"})
    assert d.inflight == 2 and d.state == "processing"
    resp = await d.handle({"cmd": "toggle"})
    assert resp["ok"] is False and "busy" in resp["msg"]


async def test_unknown_mode_and_command(make_daemon):
    d = make_daemon()
    assert (await d.handle({"cmd": "toggle", "mode": "pirate"}))["ok"] is False
    assert (await d.handle({"cmd": "dance"}))["ok"] is False


async def test_not_ready(make_daemon):
    d = make_daemon()
    d.ready.clear()
    resp = await d.handle({"cmd": "toggle"})
    assert resp["ok"] is False and "loading" in resp["msg"]


async def test_socket_roundtrip_with_worker(make_daemon):
    d = make_daemon(raw="Round trip works.")
    sock = paths.socket_path()
    await d.serve(sock)
    try:
        assert stat.S_IMODE(os.stat(sock).st_mode) == 0o600
        r = await asyncio.to_thread(cli.send, {"cmd": "status"})
        assert r == {"ok": True, "state": "idle", "msg": ""}
        r = await asyncio.to_thread(cli.send, {"cmd": "toggle", "mode": "default", "raw": False, "chord": None})
        assert r["state"] == "recording"
        r = await asyncio.to_thread(cli.send, {"cmd": "toggle"})
        assert r["msg"] == "processing"
        await asyncio.wait_for(d.queue.join(), 2)
        assert d.injector.inserted == [("Round trip works.", None)]
        assert d.inflight == 0
        # CLI exit codes
        assert await asyncio.to_thread(cli._ipc, {"cmd": "status"}) == 0
        assert await asyncio.to_thread(cli._ipc, {"cmd": "nope"}) == 1
    finally:
        await d.close()


async def test_bad_json_does_not_kill_daemon(make_daemon):
    d = make_daemon()
    sock = paths.socket_path()
    await d.serve(sock)
    try:
        reader, writer = await asyncio.open_unix_connection(str(sock))
        writer.write(b"not json\n")
        await writer.drain()
        line = await reader.readline()
        assert b'"ok": false' in line
        writer.close()
        r = await asyncio.to_thread(cli.send, {"cmd": "status"})
        assert r["ok"]
    finally:
        await d.close()


async def test_second_daemon_refuses(make_daemon):
    d = make_daemon()
    sock = paths.socket_path()
    await d.serve(sock)
    try:
        with pytest.raises(SystemExit):
            await make_daemon().serve(sock)
    finally:
        await d.close()


async def test_stale_socket_replaced(make_daemon):
    sock = paths.socket_path()
    sock.write_text("stale")
    d = make_daemon()
    await d.serve(sock)
    try:
        assert (await asyncio.to_thread(cli.send, {"cmd": "status"}))["ok"]
    finally:
        await d.close()


async def test_reload(make_daemon):
    from gillspeak import config

    d = make_daemon(cleaner=FakeCleaner("x"))
    config.ensure_defaults()
    paths.config_file().write_text(config.DEFAULT_CONFIG_TOML.replace("min_words = 12", "min_words = 3").replace('provider = "gemini"', 'provider = "none"'))
    resp = await d.handle({"cmd": "reload"})
    assert resp["ok"], resp
    assert d.cfg.gate.min_words == 3
    assert d.cleaner is None


async def test_empty_transcript_is_logged_not_silent(make_daemon, caplog):
    """Regression: a hold that transcribed to nothing left no trace in the journal."""
    import logging

    d = make_daemon(raw="   ")
    with caplog.at_level(logging.INFO, logger="gillspeakd"):
        row = await dictate(d)
    assert row is None and not d.injector.inserted
    assert "empty transcript" in caplog.text


async def test_dictated_text_stays_out_of_info_logs(make_daemon, caplog):
    """Regression (security review): rejected LLM output was logged verbatim at INFO, putting
    dictated text into the journal."""
    import logging

    d = make_daemon(cleaner=FakeCleaner("Sure! Here is the cleaned text: meeting Friday with Supabase."))
    with caplog.at_level(logging.INFO, logger="gillspeakd"):
        await dictate(d)
    info = " ".join(r.getMessage() for r in caplog.records if r.levelno >= logging.INFO)
    assert "LLM output rejected" in info
    assert "Supabase" not in info and "meeting" not in info


# -- Codex review (0.5.1) regressions ---------------------------------------------------


async def test_unexpected_cleaner_exception_still_pastes_rules_text(make_daemon):
    """Regression (Codex review #3): any non-CleanerError from clean-up skipped insertion, history
    and last_text: the dictation vanished."""
    d = make_daemon(cleaner=FakeCleaner(error=ValueError("proxy sent garbage")))
    row = await dictate(d)
    assert d.injector.inserted and d.injector.inserted[0][0].startswith("So the meeting")
    assert row["llm_status"] == "error:ValueError" and row["final_text"]
    assert d.last_text == d.injector.inserted[0][0]


async def test_inserts_are_serialized(make_daemon):
    """Regression (Codex review #4): paste-last during a finishing dictation interleaved
    copy -> settle -> paste, pasting the wrong text and corrupting clipboard restore."""
    active, peak, order = 0, 0, []

    class SlowInjector(FakeInjector):
        async def insert(self, text, chord=None):
            nonlocal active, peak
            active += 1
            peak = max(peak, active)
            order.append(("copy", text))
            await asyncio.sleep(0.02)
            order.append(("paste", text))
            active -= 1

    d = make_daemon(injector=SlowInjector())
    d.last_text = "A"
    await asyncio.gather(d._insert("B", None), d.paste_last())
    assert peak == 1
    assert order in ([("copy", "B"), ("paste", "B"), ("copy", "A"), ("paste", "A")],
                     [("copy", "A"), ("paste", "A"), ("copy", "B"), ("paste", "B")])


async def test_paste_waits_until_the_chord_is_released(make_daemon):
    """Regression (Codex review #5): a dictation finishing while Right Ctrl + Right Alt were held
    (recording the next one, or after an auto-stop) pasted as Ctrl+Alt+V, i.e. nothing."""
    d = make_daemon()
    await d.handle({"cmd": "toggle"})
    await d.handle({"cmd": "toggle"})
    job = d.queue.get_nowait()
    await d.on_hold("down")  # user starts holding for the next dictation
    task = asyncio.create_task(d.process(job))
    await asyncio.sleep(0.05)
    assert not task.done() and d.injector.inserted == []
    await d.on_hold("up")
    await asyncio.wait_for(task, 1)
    assert len(d.injector.inserted) == 1


async def test_auto_stop_during_hold_waits_for_release(make_daemon):
    d = make_daemon()
    await d.on_hold("down")
    await d.on_hold("start")
    d._auto_stop()  # audio.max_seconds reached while the keys are still held
    assert d.state == "processing" and d.chord_down
    task = asyncio.create_task(d.process(d.queue.get_nowait()))
    await asyncio.sleep(0.05)
    assert d.injector.inserted == []
    await d.on_hold("end")  # release: keyd sends end (ignored, already stopped) then up
    await d.on_hold("up")
    await asyncio.wait_for(task, 1)
    assert d.injector.inserted


async def test_helper_disconnect_releases_waiting_paste(make_daemon):
    d = make_daemon()
    await d.on_hold("down")
    d._chord_released_by_disconnect()
    assert not d.chord_down


def _write_config(**sections):
    from gillspeak import config

    config.ensure_defaults()
    text = config.DEFAULT_CONFIG_TOML
    for old, new in sections.items():
        text = text.replace(old, new)
    paths.config_file().write_text(text)


async def test_reload_can_turn_hold_to_talk_off_and_on(make_daemon, tmp_path):
    """Regression (Codex review #6): the keyd listener was only set up at startup, so
    hold_to_talk = false + `gillspeak reload` reported success but holds kept recording."""
    d = make_daemon()
    d.cfg.hotkey.keyd_socket = str(tmp_path / "keyd.sock")
    d.sync_hotkey()
    assert d._keyd is not None and not d._keyd.done()
    _write_config(**{"hold_to_talk = true": "hold_to_talk = false"})
    assert (await d.handle({"cmd": "reload"}))["ok"]
    await asyncio.sleep(0)
    assert d._keyd is None
    await d.on_hold("start")
    assert d.state == "idle"  # ignored while disabled
    _write_config(**{'keyd_socket = "/run/gillspeak-keyd/socket"': f'keyd_socket = "{tmp_path / "other.sock"}"'})
    assert (await d.handle({"cmd": "reload"}))["ok"]
    assert d._keyd is not None and d._keyd_socket == str(tmp_path / "other.sock")
    d._keyd.cancel()


async def test_reload_applies_retention_immediately(make_daemon):
    """Regression (Codex review #7): lowering keep_days only affected future writes; existing
    text stayed until a later dictation or restart triggered a purge."""
    from datetime import UTC, datetime, timedelta

    from gillspeak.history import Record

    d = make_daemon()
    old = (datetime.now(UTC) - timedelta(days=2)).isoformat(timespec="seconds")
    d.history.save(Record(created_at=old, raw_text="private", final_text="private", total_ms=1))
    _write_config(**{"keep_days = 30": "keep_days = 1"})
    assert (await d.handle({"cmd": "reload"}))["ok"]
    assert d.history.recent(1)[0]["raw_text"] is None


async def test_idle_daemon_purges_on_a_timer(make_daemon, monkeypatch):
    """Regression (Codex review #7): purging only ran at startup or after a dictation, so an idle
    daemon kept expired text indefinitely."""
    from datetime import UTC, datetime, timedelta

    from gillspeak.history import Record

    monkeypatch.setattr("gillspeak.daemon.PURGE_INTERVAL_S", 0.01)
    d = make_daemon()
    old = (datetime.now(UTC) - timedelta(days=40)).isoformat(timespec="seconds")
    d.history.save(Record(created_at=old, raw_text="private", final_text="private", total_ms=1))
    task = asyncio.create_task(d.purge_loop())
    try:
        for _ in range(100):
            if d.history.recent(1)[0]["raw_text"] is None:
                break
            await asyncio.sleep(0.01)
        assert d.history.recent(1)[0]["raw_text"] is None
    finally:
        task.cancel()


def test_service_does_not_import_the_env_file():
    """Regression (Codex review #9): EnvironmentFile= froze the fallback API key into the daemon's
    environment, which then beat the rotated value in ~/.config/gillspeak/env on `gillspeak reload`."""
    from pathlib import Path

    unit = (Path(__file__).parent.parent / "systemd" / "gillspeakd.service").read_text()
    assert "EnvironmentFile" not in unit


def test_rotated_env_file_key_is_read_fresh(monkeypatch):
    from gillspeak import secrets

    monkeypatch.setattr(secrets, "_keyring_get", lambda user: None)
    secrets.write_env_file("GEMINI_API_KEY", "old")
    assert secrets.get_api_key() == "old"
    secrets.write_env_file("GEMINI_API_KEY", "new")
    assert secrets.get_api_key() == "new"


async def test_default_config_is_local_only(make_daemon):
    """The shipped default never calls the cloud clean-up, even for a long dictation the gate would send."""
    assert Config().llm.provider == "none"
    d = make_daemon(cfg=Config())  # LONG_RAW: long enough, and with a correction
    row = await dictate(d)
    assert d.cleaner.calls == []
    assert row["llm_status"] == "skipped" and row["gate_reason"] == "provider_none"
    assert d.injector.inserted[0][0].startswith("So the meeting with Supabase folks")  # the rules text
