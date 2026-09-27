"""Daemon pipeline and CLI <-> daemon round-trips, with every external dependency faked."""

import asyncio
import os
import stat

import numpy as np
import pytest

from murmur import cli, paths
from murmur.cleaner import CleanerError, CleanResult
from murmur.config import Config, Dictionary
from murmur.daemon import Daemon
from murmur.history import History
from murmur.inject import InjectError

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


@pytest.fixture
def make_daemon(tmp_path):
    created = []

    def factory(raw=LONG_RAW, cleaner=None, injector=None, cfg=None, dictionary=None):
        d = Daemon(
            cfg or Config(),
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
    from murmur import config

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
    with caplog.at_level(logging.INFO, logger="murmurd"):
        row = await dictate(d)
    assert row is None and not d.injector.inserted
    assert "empty transcript" in caplog.text


async def test_dictated_text_stays_out_of_info_logs(make_daemon, caplog):
    """Regression (security review): rejected LLM output was logged verbatim at INFO, putting
    dictated text into the journal."""
    import logging

    d = make_daemon(cleaner=FakeCleaner("Sure! Here is the cleaned text: meeting Friday with Supabase."))
    with caplog.at_level(logging.INFO, logger="murmurd"):
        await dictate(d)
    info = " ".join(r.getMessage() for r in caplog.records if r.levelno >= logging.INFO)
    assert "LLM output rejected" in info
    assert "Supabase" not in info and "meeting" not in info
