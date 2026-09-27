"""murmurd: long-running daemon. Keeps the ASR model warm and serves the thin CLI over a Unix socket."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import signal
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np

from . import config as config_mod
from . import gate, paths, validate
from .audio import SAMPLE_RATE, Recorder, RecorderError, write_wav
from .cleaner import PROMPT_VERSION, CleanerError, make_cleaner
from .config import Config, Dictionary
from .history import History, Record
from .inject import InjectError, make_injector
from .notify import Notifier
from .rules import RulesEngine
from .vad import make_vad, split_long, trim

log = logging.getLogger("murmurd")

MAX_INFLIGHT = 2
RATE_LIMIT_BACKOFF_S = 60.0
KEYD_RETRY_S = 5.0
PURGE_INTERVAL_S = 3600.0  # history retention runs on a timer, not only when dictating
CHORD_WAIT_EXTRA_S = 30.0  # longest we hold a paste for the chord release, beyond audio.max_seconds
DEBUG_AUDIO_KEEP_DAYS = 7


@dataclass
class JobOptions:
    mode: str = "default"
    raw: bool = False
    chord: str | None = None


@dataclass
class Job:
    audio: np.ndarray
    opts: JobOptions
    stopped_at: float


class Daemon:
    def __init__(
        self,
        cfg: Config,
        dictionary: Dictionary,
        *,
        recorder: Any = None,
        transcriber: Any = None,
        vad: Any = None,
        cleaner: Any = None,
        injector: Any = None,
        history: Any = None,
        notifier: Any = None,
    ):
        self.cfg = cfg
        self.dictionary = dictionary
        self.rules = RulesEngine(cfg.rules, dictionary)
        self.recorder = recorder
        self.transcriber = transcriber
        self.vad = vad
        self.cleaner = cleaner
        self.injector = injector or make_injector(cfg.inject)
        self.history = history
        self.notifier = notifier or Notifier(cfg.notify.enabled, cfg.sounds.enabled)
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="asr")
        self.queue: asyncio.Queue[Job] = asyncio.Queue()
        self.inflight = 0
        self.opts = JobOptions()
        self.holding = False  # the current recording was started by a murmur-keyd hold
        self.chord_down = False  # Right Ctrl + Right Alt physically held (keyd "down" .. "up")
        self._chord_up = asyncio.Event()
        self._chord_up.set()
        self._insert_lock = asyncio.Lock()  # one copy -> paste -> restore at a time
        self._keyd_socket: str | None = None
        self.backoff_until = 0.0
        self.last_text: str | None = None
        self.ready = asyncio.Event()
        self.load_error: str | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._server: asyncio.base_events.Server | None = None
        self._worker: asyncio.Task[None] | None = None
        self._keyd: asyncio.Task[None] | None = None
        self._tasks: set[asyncio.Task[Any]] = set()

    # -- lifecycle -------------------------------------------------------------
    def _spawn(self, coro: Any) -> asyncio.Task[Any]:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def load(self) -> None:
        """Load models (slow) in the executor. The socket is already serving meanwhile."""
        loop = asyncio.get_running_loop()
        cfg = self.cfg
        try:
            if self.history is None:
                self.history = History(paths.history_db(), cfg.history.keep_days)
                await asyncio.to_thread(self.history.purge)
            if self.vad is None:
                self.vad = await loop.run_in_executor(self.executor, make_vad, cfg.vad.engine, cfg.asr.model_path)
            if self.transcriber is None:
                from .asr import make_transcriber

                t0 = time.monotonic()
                self.transcriber = await loop.run_in_executor(
                    self.executor,
                    lambda: make_transcriber(
                        cfg.asr.engine, cfg.asr.model_path, cfg.asr.num_threads, self.dictionary.bias if cfg.asr.hotwords else None
                    ),
                )
                log.info("ASR %s loaded in %.1fs", cfg.asr.engine, time.monotonic() - t0)
            if self.recorder is None:
                self.recorder = Recorder(
                    cfg.audio.device, keep_open=cfg.audio.keep_mic_open, max_seconds=cfg.audio.max_seconds, on_max=self._on_max
                )
                try:
                    self.recorder.start_idle()
                except RecorderError as e:
                    log.warning("microphone not available yet: %s", e)
            self._make_cleaner()
            if self.cleaner is not None:
                self._spawn(self.cleaner.warm())
        except Exception as e:
            log.exception("startup failed")
            self.load_error = str(e)
            hint = "Run `murmur download-models`" if isinstance(e, FileNotFoundError) else "See `journalctl --user -u murmurd`"
            await self.notifier.error("Murmur failed to start", f"{e}. {hint}")
        finally:
            self.ready.set()

    def _make_cleaner(self) -> None:
        if self.cleaner is not None or self.cfg.llm.provider == "none":
            return
        try:
            self.cleaner = make_cleaner(self.cfg)
        except CleanerError as e:
            log.warning("LLM clean-up disabled: %s", e)
            self._spawn(self.notifier.error("Gemini API key missing", "Run `murmur set-key`, then `murmur reload`"))

    async def serve(self, sock: Path) -> None:
        self._loop = asyncio.get_running_loop()
        sock.parent.mkdir(parents=True, exist_ok=True)
        if sock.exists():
            if await _socket_alive(sock):
                raise SystemExit(f"murmurd already running ({sock})")
            sock.unlink()
        old_umask = os.umask(0o177)
        try:
            self._server = await asyncio.start_unix_server(self._handle_conn, path=str(sock))
        finally:
            os.umask(old_umask)
        os.chmod(sock, 0o600)
        self._worker = asyncio.create_task(self._work())
        log.info("listening on %s", sock)

    async def close(self) -> None:
        if self._server:
            self._server.close()
            await self._server.wait_closed()
        if self._worker:
            self._worker.cancel()
        if self._keyd:
            self._keyd.cancel()
        if self.recorder is not None:
            self.recorder.close()
        if self.cleaner is not None:
            await self.cleaner.aclose()
        self.executor.shutdown(wait=False, cancel_futures=True)

    # -- IPC -------------------------------------------------------------------
    async def _handle_conn(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while line := await reader.readline():
                try:
                    req = json.loads(line)
                    if not isinstance(req, dict):
                        raise ValueError("request must be an object")
                    resp = await self.handle(req)
                except Exception as e:  # never let one bad request kill the daemon
                    log.exception("request failed")
                    resp = {"ok": False, "state": self.state, "msg": str(e)}
                writer.write((json.dumps(resp) + "\n").encode())
                await writer.drain()
        except (ConnectionError, asyncio.IncompleteReadError):
            pass
        finally:
            writer.close()

    @property
    def state(self) -> str:
        if self.recorder is not None and self.recorder.is_recording:
            return "recording"
        return "processing" if self.inflight else "idle"

    def _resp(self, ok: bool = True, msg: str = "") -> dict[str, Any]:
        return {"ok": ok, "state": self.state, "msg": msg}

    async def handle(self, req: dict[str, Any]) -> dict[str, Any]:
        cmd = req.get("cmd")
        if cmd == "status":
            msg = "loading models" if not self.ready.is_set() else (f"error: {self.load_error}" if self.load_error else "")
            return self._resp(True, msg)
        if cmd == "toggle":
            return await self.toggle(req)
        if cmd == "cancel":
            return await self.cancel()
        if cmd == "paste_last":
            return await self.paste_last()
        if cmd == "reload":
            return await self.reload()
        return self._resp(False, f"unknown command {cmd!r}")

    async def toggle(self, req: dict[str, Any]) -> dict[str, Any]:
        if not self.ready.is_set():
            return self._resp(False, "still loading the speech model, try again in a moment")
        if self.load_error or self.recorder is None:
            return self._resp(False, f"daemon not ready: {self.load_error}")
        if self.recorder.is_recording:
            return self._stop()
        if self.inflight >= MAX_INFLIGHT:
            return self._resp(False, "busy: two dictations already processing")
        mode = str(req.get("mode") or "default")
        if mode not in self.cfg.modes:
            return self._resp(False, f"unknown mode {mode!r}; define it under [modes]")
        chord = req.get("chord")
        if chord is not None and chord not in ("ctrl+v", "ctrl+shift+v"):
            return self._resp(False, f"unknown chord {chord!r}")
        try:
            self.recorder.start()
        except RecorderError as e:
            await self.notifier.error("No microphone", f"{e}. Check Settings → Sound → Input")
            return self._resp(False, str(e))
        self.opts = JobOptions(mode=mode, raw=bool(req.get("raw")), chord=chord)
        self._spawn(self.notifier.listening(mode, self.opts.raw))
        if self.cleaner is not None and not self.opts.raw and getattr(self.cleaner, "needs_warm", lambda: False)():
            self._spawn(self.cleaner.warm())  # re-open the TLS connection while the user talks
        return self._resp(True, "recording")

    def _stop(self) -> dict[str, Any]:
        self.holding = False
        audio = self.recorder.stop()
        self.inflight += 1
        self.queue.put_nowait(Job(audio, self.opts, time.monotonic()))
        self._spawn(self.notifier.processing())
        return self._resp(True, "processing")

    def _on_max(self) -> None:
        """Called from the audio thread when audio.max_seconds is reached."""
        if self._loop is not None:
            self._loop.call_soon_threadsafe(self._auto_stop)

    def _auto_stop(self) -> None:
        if self.recorder is not None and self.recorder.is_recording:
            log.info("max recording length reached; stopping")
            self._stop()

    async def cancel(self) -> dict[str, Any]:
        self.holding = False
        if self.recorder is not None and self.recorder.is_recording:
            self.recorder.cancel()
            await self.notifier.clear()
            return self._resp(True, "cancelled")
        return self._resp(True, "nothing to cancel")

    async def purge_loop(self) -> None:
        while True:
            await asyncio.sleep(PURGE_INTERVAL_S)
            if self.history is not None:
                await asyncio.to_thread(self.history.purge)

    # -- hold-to-talk (murmur-keyd) ---------------------------------------------
    def _set_chord(self, down: bool) -> None:
        self.chord_down = down
        if down:
            self._chord_up.clear()
        else:
            self._chord_up.set()

    def _chord_released_by_disconnect(self) -> None:
        """No helper means no reliable key state; don't hold pastes forever."""
        self._set_chord(False)

    def sync_hotkey(self) -> None:
        """Start, stop or re-point the murmur-keyd listener to match cfg.hotkey (startup and reload)."""
        want = self.cfg.hotkey.keyd_socket if self.cfg.hotkey.hold_to_talk else None
        if self._keyd is not None and (want is None or want != self._keyd_socket):
            self._keyd.cancel()
            self._keyd, self._keyd_socket = None, None
            self._chord_released_by_disconnect()
            if self.holding:
                self._spawn(self.cancel())
        if want is not None and self._keyd is None:
            self._keyd_socket = want
            self._keyd = asyncio.create_task(self.keyd_listener(want))

    async def on_hold(self, event: str) -> None:
        """down/up: chord physically held/released (pastes wait for up). start: begin recording
        unless one is already running (e.g. from Ctrl+Space). end: stop and paste.
        cancel: another key joined the chord, so discard."""
        if event in ("down", "up"):
            self._set_chord(event == "down")
            return
        if not self.cfg.hotkey.hold_to_talk:
            return
        recording = self.recorder is not None and self.recorder.is_recording
        log.info("hold %s (recording=%s, holding=%s)", event, recording, self.holding)
        if event == "start" and not recording:
            resp = await self.toggle({})
            self.holding = resp["ok"] and resp["msg"] == "recording"
            if not resp["ok"]:
                log.info("hold start refused: %s", resp["msg"])
        elif event == "end" and self.holding and recording:
            self._stop()
        elif event == "cancel" and self.holding:
            await self.cancel()

    async def keyd_listener(self, path: str) -> None:
        """Follow murmur-keyd's event socket, reconnecting quietly if the helper isn't running."""
        warned = False
        while True:
            try:
                reader, writer = await asyncio.open_unix_connection(path)
            except OSError as e:
                if not warned:
                    log.info("hold-to-talk off: murmur-keyd not reachable at %s (%s); retrying", path, e.strerror or e)
                    warned = True
                await asyncio.sleep(KEYD_RETRY_S)
                continue
            log.info("hold-to-talk on (Right Ctrl + Right Alt) via %s", path)
            warned = False
            try:
                while line := await reader.readline():
                    try:
                        event = json.loads(line).get("event")
                    except (ValueError, AttributeError):
                        continue
                    await self.on_hold(str(event))
            except ConnectionError:
                pass
            finally:
                writer.close()
            self._chord_released_by_disconnect()
            if self.holding:  # helper went away mid-hold: don't leave the mic recording
                await self.cancel()
            log.info("murmur-keyd disconnected; retrying")
            await asyncio.sleep(KEYD_RETRY_S)

    async def paste_last(self) -> dict[str, Any]:
        text = self.last_text
        if text is None and self.history is not None:
            text = await asyncio.to_thread(self.history.last_final_text)
        if not text:
            return self._resp(False, "nothing to paste yet")
        status = await self._insert(text, None)
        return self._resp(status in ("pasted", "copied"), status)

    async def reload(self) -> dict[str, Any]:
        try:
            cfg = config_mod.load()
            dictionary = config_mod.load_dictionary()
        except config_mod.ConfigError as e:
            return self._resp(False, f"config error: {e}")
        restart = []
        if (cfg.asr, cfg.vad) != (self.cfg.asr, self.cfg.vad) or (cfg.asr.hotwords and dictionary.bias != self.dictionary.bias):
            restart.append("ASR/VAD settings")
        if cfg.audio != self.cfg.audio:
            restart.append("[audio]")
        self.cfg, self.dictionary = cfg, dictionary
        self.rules = RulesEngine(cfg.rules, dictionary)
        self.injector = make_injector(cfg.inject)
        self.notifier.notifications = cfg.notify.enabled
        self.notifier.sounds = cfg.sounds.enabled
        if self.history is not None:
            self.history.keep_days = cfg.history.keep_days
            await asyncio.to_thread(self.history.purge)  # a shorter retention applies now, not at the next purge
        self.sync_hotkey()
        if self.cleaner is not None:
            await self.cleaner.aclose()
            self.cleaner = None
        self._make_cleaner()
        msg = "reloaded"
        if restart:
            msg += f"; restart murmurd to apply {', '.join(restart)}"
        return self._resp(True, msg)

    # -- pipeline --------------------------------------------------------------
    async def _work(self) -> None:
        while True:
            job = await self.queue.get()
            try:
                await self.process(job)
            except Exception:
                log.exception("processing failed")
                await self.notifier.error("Dictation failed", "See `journalctl --user -u murmurd`")
            finally:
                self.inflight -= 1
                self.queue.task_done()

    async def _run(self, fn: Any, *args: Any) -> Any:
        return await asyncio.get_running_loop().run_in_executor(self.executor, fn, *args)

    async def process(self, job: Job) -> None:
        cfg, opts = self.cfg, job.opts
        rec = Record(mode=opts.mode, asr_engine=cfg.asr.engine, audio_seconds=round(len(job.audio) / SAMPLE_RATE, 2))
        if cfg.debug.save_audio:
            await asyncio.to_thread(self._save_debug_audio, job.audio)

        speech = await self._run(trim, job.audio, self.vad, cfg.vad.min_speech_s)
        if speech is None:
            log.info("no speech detected (%.1fs of audio); discarding", rec.audio_seconds)
            await self.notifier.clear()
            return

        t_asr = time.monotonic()
        try:
            chunks = await self._run(split_long, speech, self.vad)
            parts = [await self._run(self.transcriber.transcribe, c) for c in chunks]
        except Exception as e:
            log.exception("ASR failed")
            await self.notifier.error("Transcription failed", str(e)[:120])
            return
        rec.asr_ms = int((time.monotonic() - t_asr) * 1000)
        raw = " ".join(p for p in parts if p).strip()
        rec.raw_text = raw
        text = self.rules.apply(raw)
        rec.rules_text = text
        if not text.strip():
            log.info("empty transcript (%.1fs of audio, %d raw chars); discarding", rec.audio_seconds, len(raw))
            log.debug("empty transcript raw text: %r", raw[:80])  # dictated text stays out of the journal by default
            await self.notifier.clear()
            return

        use_llm, reason = gate.decide(
            text,
            mode=opts.mode,
            raw=opts.raw,
            provider=cfg.llm.provider,
            always=cfg.llm.always,
            cfg=cfg.gate,
            backoff_until=self.backoff_until,
        )
        rec.gate_llm, rec.gate_reason = int(use_llm), reason
        final, status = text, "skipped"
        notice: tuple[str, str] | None = None
        if use_llm:
            final, status, notice = await self._clean(text, opts, rec)
        rec.llm_status = status

        inject_status = await self._insert(final, opts.chord)
        rec.inject_status = inject_status
        rec.final_text = final
        rec.words = gate.word_count(final)
        rec.total_ms = int((time.monotonic() - job.stopped_at) * 1000)
        self.last_text = final
        if self.history is not None:
            await asyncio.to_thread(self.history.save, rec)
        log.info("dictation: %d words, llm=%s (%s), %d ms, %s", rec.words, status, reason, rec.total_ms, inject_status)
        if inject_status == "pasted":
            if notice:
                await self.notifier.info(*notice)
            else:
                await self.notifier.clear()

    async def _clean(self, text: str, opts: JobOptions, rec: Record) -> tuple[str, str, tuple[str, str] | None]:
        if self.cleaner is None:
            return text, "error:no_cleaner", None
        rec.llm_model = self.cleaner.model
        rec.prompt_ver = PROMPT_VERSION
        t0 = time.monotonic()
        try:
            res = await self.cleaner.clean(
                text, mode=opts.mode, instructions=self.cfg.llm.instructions, bias_terms=self.dictionary.bias
            )
        except CleanerError as e:
            rec.llm_ms = int((time.monotonic() - t0) * 1000)
            log.warning("LLM %s: %s", e.kind, e)
            if e.kind == "rate_limited":
                self.backoff_until = time.monotonic() + RATE_LIMIT_BACKOFF_S
                return text, e.kind, ("Rate limited — pasted uncleaned", "LLM paused for 60 s")
            if e.kind == "offline":
                return text, e.kind, ("Offline — pasted uncleaned", "")
            if e.kind == "auth":
                self._spawn(self.notifier.error("Gemini rejected the request", "Bad API key or model ID? Run `murmur doctor`"))
            return text, e.kind, None
        except Exception as e:  # never lose the dictation to a clean-up bug: fall back to the rules text
            rec.llm_ms = int((time.monotonic() - t0) * 1000)
            log.exception("LLM clean-up failed unexpectedly")
            return text, f"error:{type(e).__name__}", None
        rec.llm_ms = res.llm_ms or int((time.monotonic() - t0) * 1000)
        rec.input_tokens, rec.output_tokens = res.input_tokens, res.output_tokens
        ok, why = validate.check(
            text, res.text, opts.mode, bias_terms=self.dictionary.bias, check_bias=self.cfg.llm.check_bias_terms
        )
        if not ok:
            log.info("LLM output rejected (%s); pasting the rules text", why)
            log.debug("rejected LLM output: %r", res.text[:200])
            return text, f"rejected:{why}", None
        return self.rules.apply_dictionary(res.text), "ok", None

    async def _insert(self, text: str, chord: str | None) -> str:
        """Every transcript ends up pasted or, failing that, on the clipboard."""
        async with self._insert_lock:
            await self._wait_for_chord_release()
            return await self._insert_now(text, chord)

    async def _wait_for_chord_release(self) -> None:
        """A synthetic Ctrl+V sent while Right Ctrl + Right Alt are held arrives as Ctrl+Alt+V."""
        if self._chord_up.is_set():
            return
        log.info("waiting for Right Ctrl + Right Alt to be released before pasting")
        try:
            await asyncio.wait_for(self._chord_up.wait(), self.cfg.audio.max_seconds + CHORD_WAIT_EXTRA_S)
        except TimeoutError:
            log.warning("chord still reported held; pasting anyway")

    async def _insert_now(self, text: str, chord: str | None) -> str:
        try:
            await self.injector.insert(text, chord)
            return "pasted"
        except InjectError as e:
            log.warning("inject: %s", e)
            if e.copied:
                await self.notifier.error("Copied — press Ctrl+V", f"Auto-paste failed ({e.kind} not running?). Run `murmur doctor`")
                return "copied"
            await self.notifier.error("Could not paste or copy the text", f"{e}. Use `murmur history -n 1` to retrieve it")
            return "failed"

    def _save_debug_audio(self, audio: np.ndarray) -> None:
        d = paths.cache_dir() / "audio"
        write_wav(d / f"{datetime.now():%Y%m%d-%H%M%S}.wav", audio)
        cutoff = time.time() - DEBUG_AUDIO_KEEP_DAYS * 86400
        for f in d.glob("*.wav"):
            if f.stat().st_mtime < cutoff:
                f.unlink(missing_ok=True)


async def _socket_alive(sock: Path) -> bool:
    try:
        _, writer = await asyncio.wait_for(asyncio.open_unix_connection(str(sock)), 0.5)
        writer.close()
        return True
    except (TimeoutError, OSError):
        return False


async def amain() -> None:
    try:
        cfg = config_mod.load()
        dictionary = config_mod.load_dictionary()
    except config_mod.ConfigError as e:
        log.error("config error: %s", e)
        raise SystemExit(1) from e
    logging.getLogger().setLevel(cfg.debug.log_level.upper())
    daemon = Daemon(cfg, dictionary)
    await daemon.serve(paths.socket_path())
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)
    await daemon.load()
    daemon.sync_hotkey()
    purge = asyncio.create_task(daemon.purge_loop())
    await stop.wait()
    purge.cancel()
    log.info("shutting down")
    await daemon.close()
    try:
        paths.socket_path().unlink()
    except OSError:
        pass


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s", stream=sys.stderr)
    logging.getLogger("httpx").setLevel(logging.WARNING)  # request logs would include URLs; never the key header
    asyncio.run(amain())


if __name__ == "__main__":
    main()
