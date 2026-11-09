"""Pause whatever is playing while you dictate, then resume only what was paused here (MPRIS over gdbus)."""

from __future__ import annotations

import asyncio
import logging
import re
from typing import Any

from .inject import run

log = logging.getLogger(__name__)

OBJECT_PATH = "/org/mpris/MediaPlayer2"
PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
_PLAYER_NAME = re.compile(r"'(org\.mpris\.MediaPlayer2\.[^']+)'")
TIMEOUT_S = 1.0


class MediaPauser:
    """Never raises: a player that misbehaves must not get in the way of dictation."""

    def __init__(self, enabled: bool = True, runner: Any = run):
        self.enabled = enabled
        self._run = runner
        self._paused: list[str] = []
        self._lock = asyncio.Lock()  # a quick stop must wait for the pause that is still running

    async def _gdbus(self, dest: str, path: str, method: str, *args: str) -> str | None:
        argv = ["gdbus", "call", "--session", "--dest", dest, "--object-path", path, "--method", method, *args]
        try:
            code, out, _ = await self._run(argv, timeout=TIMEOUT_S)
        except (TimeoutError, OSError) as e:
            log.debug("gdbus %s failed: %s", method, e)
            return None
        return out.decode(errors="replace") if code == 0 else None

    async def _players(self) -> list[str]:
        out = await self._gdbus("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus.ListNames")
        return _PLAYER_NAME.findall(out) if out else []

    async def _is_playing(self, player: str) -> bool:
        out = await self._gdbus(player, OBJECT_PATH, "org.freedesktop.DBus.Properties.Get", PLAYER_INTERFACE, "PlaybackStatus")
        return out is not None and "'Playing'" in out

    async def pause(self) -> None:
        if not self.enabled:
            return
        async with self._lock:
            for player in await self._players():
                if await self._is_playing(player) and await self._gdbus(player, OBJECT_PATH, f"{PLAYER_INTERFACE}.Pause") is not None:
                    self._paused.append(player)

    async def resume(self) -> None:
        async with self._lock:
            players, self._paused = self._paused, []
            for player in players:
                await self._gdbus(player, OBJECT_PATH, f"{PLAYER_INTERFACE}.Play")
