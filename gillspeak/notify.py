"""Desktop notifications (notify-send, replaced in place) and short sounds (pw-play)."""

from __future__ import annotations

import asyncio
import logging
import shutil
from importlib import resources
from pathlib import Path

log = logging.getLogger(__name__)


def sound_path(name: str) -> Path:
    return Path(str(resources.files("gillspeak").joinpath("assets", "sounds", f"{name}.wav")))


class Notifier:
    def __init__(self, notifications: bool = True, sounds: bool = True):
        self.notifications = notifications and shutil.which("notify-send") is not None
        self.sounds = sounds and shutil.which("pw-play") is not None
        self._id: int | None = None
        self._lock = asyncio.Lock()
        self._sounds: set[asyncio.Task[None]] = set()  # keep references so playing sounds aren't garbage-collected

    async def _show(self, summary: str, body: str = "", *, urgency: str = "normal", timeout_ms: int = 0, transient: bool = True) -> None:
        if not self.notifications:
            return
        argv = ["notify-send", "-a", "gillspeak", "-u", urgency, "-t", str(timeout_ms), "--print-id"]
        if transient:
            argv += ["-h", "boolean:transient:true"]
        async with self._lock:
            if self._id is not None:
                argv += ["--replace-id", str(self._id)]
            argv += ["--", summary] + ([body] if body else [])
            try:
                proc = await asyncio.create_subprocess_exec(*argv, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
                out, _ = await asyncio.wait_for(proc.communicate(), 2.0)
                self._id = int(out.decode().strip().splitlines()[-1])
            except (TimeoutError, OSError, ValueError, IndexError) as e:
                log.debug("notify-send failed: %s", e)

    async def clear(self) -> None:
        async with self._lock:
            nid, self._id = self._id, None
        if nid is None or not self.notifications:
            return
        try:
            proc = await asyncio.create_subprocess_exec(
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.CloseNotification", str(nid),
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
            )
            await asyncio.wait_for(proc.wait(), 2.0)
        except (TimeoutError, OSError) as e:
            log.debug("close notification failed: %s", e)

    def play(self, name: str) -> None:
        if not self.sounds:
            return
        path = sound_path(name)
        if not path.exists():
            return
        try:
            task = asyncio.get_running_loop().create_task(self._play(path))
        except RuntimeError:
            return
        self._sounds.add(task)
        task.add_done_callback(self._sounds.discard)

    async def _play(self, path: Path) -> None:
        try:
            proc = await asyncio.create_subprocess_exec("pw-play", str(path), stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
            await proc.wait()
        except OSError as e:
            log.debug("pw-play failed: %s", e)

    # -- high-level ------------------------------------------------------------
    async def listening(self, mode: str = "default", raw: bool = False) -> None:
        self.play("start")
        tag = " (raw)" if raw else (f" ({mode})" if mode != "default" else "")
        await self._show(f"🎙 Listening…{tag}")

    async def processing(self) -> None:
        self.play("stop")
        await self._show("⏳ Processing…")

    async def info(self, summary: str, body: str = "", timeout_ms: int = 2500) -> None:
        await self._show(summary, body, timeout_ms=timeout_ms)

    async def error(self, summary: str, hint: str = "") -> None:
        """Persistent (critical) notification with a one-line fix hint."""
        self.play("error")
        await self._show(f"⚠ {summary}", hint, urgency="critical", timeout_ms=0, transient=False)
        async with self._lock:
            self._id = None  # don't let the next "Listening…" replace the error
