"""Put text at the cursor: clipboard + synthetic paste chord (Wayland: wl-clipboard + ydotool)."""

from __future__ import annotations

import asyncio
import logging
import os
from pathlib import Path
from typing import Protocol

from .config import InjectConfig

log = logging.getLogger(__name__)

# Linux input keycodes: KEY_LEFTCTRL=29, KEY_LEFTSHIFT=42, KEY_V=47
CHORD_KEYS = {
    "ctrl+v": ["29:1", "47:1", "47:0", "29:0"],
    "ctrl+shift+v": ["29:1", "42:1", "47:1", "47:0", "42:0", "29:0"],
}
TYPE_MAX_CHARS = 200
TEXT_MIME_HINTS = ("text/", "UTF8_STRING", "STRING", "TEXT")


class InjectError(Exception):
    """copied=True means the text is on the clipboard even though the paste failed."""

    def __init__(self, message: str, *, copied: bool, kind: str = "error"):
        super().__init__(message)
        self.copied = copied
        self.kind = kind


class Injector(Protocol):
    async def insert(self, text: str, chord: str | None = None) -> None: ...

    async def copy(self, text: str) -> None: ...


async def run(
    argv: list[str], stdin: bytes | None = None, *, capture: bool = True, timeout: float = 3.0, env: dict[str, str] | None = None
) -> tuple[int, bytes, bytes]:
    """Run a helper. capture=False for tools that fork a clipboard server (wl-copy, xclip):
    a forked child would keep our pipes open and we'd wait forever."""
    proc = await asyncio.create_subprocess_exec(
        *argv,
        stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE if capture else asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.PIPE if capture else asyncio.subprocess.DEVNULL,
        env=env,
    )
    try:
        out, err = await asyncio.wait_for(proc.communicate(stdin), timeout)
    except asyncio.TimeoutError:
        proc.kill()
        await proc.wait()
        raise
    return proc.returncode or 0, out or b"", err or b""


SETUP_SOCKET = Path("/run/ydotoold/socket")  # what scripts/setup-fedora.sh configures


def ydotool_socket() -> Path:
    env = os.environ.get("YDOTOOL_SOCKET")
    if env:
        return Path(env)
    candidates = [SETUP_SOCKET]
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if runtime:
        candidates.append(Path(runtime) / ".ydotool_socket")
    for c in candidates:
        if c.exists():
            return c
    return Path("/tmp/.ydotool_socket")  # ydotoold's built-in default


def is_typeable(text: str) -> bool:
    return len(text) < TYPE_MAX_CHARS and text.isascii() and "\n" not in text and "\t" not in text


class _ClipboardInjector:
    def __init__(self, cfg: InjectConfig):
        self.cfg = cfg
        self._restore_task: asyncio.Task[None] | None = None

    async def _get_clipboard(self) -> str | None:
        raise NotImplementedError

    async def copy(self, text: str) -> None:
        raise NotImplementedError

    async def _paste(self, chord: str) -> None:
        raise NotImplementedError

    async def _type(self, text: str) -> bool:
        return False

    async def insert(self, text: str, chord: str | None = None) -> None:
        chord = chord or self.cfg.paste_chord
        if chord not in CHORD_KEYS:
            raise ValueError(f"unknown chord {chord!r}")
        if self.cfg.method == "type" and is_typeable(text) and await self._type(text):
            return
        if self._restore_task and not self._restore_task.done():
            # A restore from the previous paste is pending; run it now so we save the user's real clipboard.
            await self._restore_task
        previous = await self._get_clipboard() if self.cfg.restore_clipboard else None
        await self.copy(text)
        await asyncio.sleep(self.cfg.settle_ms / 1000)
        await self._paste(chord)  # raises InjectError(copied=True)
        if self.cfg.restore_clipboard and previous is not None and previous != text:
            self._restore_task = asyncio.create_task(self._restore(previous, text))

    async def _restore(self, previous: str, ours: str) -> None:
        await asyncio.sleep(self.cfg.restore_delay_ms / 1000)
        try:
            # Only restore if the clipboard still holds our text (the user may have copied something since).
            if await self._get_clipboard() == ours:
                await self.copy(previous)
        except Exception as e:
            log.debug("clipboard restore failed: %s", e)


class WaylandInjector(_ClipboardInjector):
    async def _get_clipboard(self) -> str | None:
        try:
            code, out, _ = await run(["wl-paste", "--list-types"], timeout=1.0)
            if code != 0 or not any(t.startswith(TEXT_MIME_HINTS) for t in out.decode(errors="replace").split()):
                return None
            code, out, _ = await run(["wl-paste", "--no-newline", "--type", "text"], timeout=1.0)
            return out.decode("utf-8") if code == 0 else None
        except (OSError, asyncio.TimeoutError, UnicodeDecodeError):
            return None

    async def copy(self, text: str) -> None:
        try:
            code, _, _ = await run(["wl-copy", "--type", "text/plain;charset=utf-8"], stdin=text.encode("utf-8"), capture=False)
        except (OSError, asyncio.TimeoutError) as e:
            raise InjectError(f"wl-copy failed: {e}", copied=False, kind="clipboard") from e
        if code != 0:
            raise InjectError(f"wl-copy exited {code}", copied=False, kind="clipboard")

    async def _ydotool(self, args: list[str], stdin: bytes | None = None) -> None:
        sock = ydotool_socket()
        if not sock.exists():
            raise InjectError(f"ydotool socket {sock} not found", copied=True, kind="ydotool")
        try:
            code, _, err = await run(["ydotool", *args], stdin=stdin, env={**os.environ, "YDOTOOL_SOCKET": str(sock)})
        except (OSError, asyncio.TimeoutError) as e:
            raise InjectError(f"ydotool failed: {e}", copied=True, kind="ydotool") from e
        msg = err.decode(errors="replace")
        if code != 0 or "failed to connect" in msg.lower():
            raise InjectError(f"ydotool: {msg.strip() or f'exit {code}'}", copied=True, kind="ydotool")

    async def _paste(self, chord: str) -> None:
        await self._ydotool(["key", *CHORD_KEYS[chord]])

    async def _type(self, text: str) -> bool:
        try:
            await self._ydotool(["type", "--key-delay", "2", "--file", "-"], stdin=text.encode())
            return True
        except InjectError as e:
            log.info("type failed (%s); falling back to paste", e)
            return False


class X11Injector(_ClipboardInjector):
    async def _get_clipboard(self) -> str | None:
        try:
            code, out, _ = await run(["xclip", "-selection", "clipboard", "-o", "-t", "UTF8_STRING"], timeout=1.0)
            return out.decode("utf-8") if code == 0 else None
        except (OSError, asyncio.TimeoutError, UnicodeDecodeError):
            return None

    async def copy(self, text: str) -> None:
        try:
            code, _, _ = await run(["xclip", "-selection", "clipboard", "-i"], stdin=text.encode("utf-8"), capture=False)
        except (OSError, asyncio.TimeoutError) as e:
            raise InjectError(f"xclip failed: {e}", copied=False, kind="clipboard") from e
        if code != 0:
            raise InjectError(f"xclip exited {code}", copied=False, kind="clipboard")

    async def _paste(self, chord: str) -> None:
        try:
            code, _, err = await run(["xdotool", "key", "--clearmodifiers", chord])
        except (OSError, asyncio.TimeoutError) as e:
            raise InjectError(f"xdotool failed: {e}", copied=True, kind="xdotool") from e
        if code != 0:
            raise InjectError(f"xdotool: {err.decode(errors='replace').strip()}", copied=True, kind="xdotool")

    async def _type(self, text: str) -> bool:
        code, _, _ = await run(["xdotool", "type", "--clearmodifiers", "--delay", "2", "--file", "-"], stdin=text.encode())
        return code == 0


def session_type() -> str:
    st = os.environ.get("XDG_SESSION_TYPE", "").lower()
    if st in ("wayland", "x11"):
        return st
    if os.environ.get("WAYLAND_DISPLAY"):
        return "wayland"
    if os.environ.get("DISPLAY"):
        return "x11"
    return "wayland"


def make_injector(cfg: InjectConfig) -> _ClipboardInjector:
    if session_type() == "x11":
        return X11Injector(cfg)
    return WaylandInjector(cfg)
