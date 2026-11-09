"""Live microphone levels for an on-screen animation.

While recording, gillspeakd cuts the audio into 10 ms windows and sends one 16-byte frame per
window over a Unix socket only this user can open: u32 seq, f32 min, f32 max, f32 peak_dbfs, in
native byte order. That is the layout Voxtype's visualizer reads, so
`voxtype-osd-gtk4 --socket $XDG_RUNTIME_DIR/gillspeak-levels.sock` can draw it. The visualizer
shows itself while frames arrive and hides when they stop, so no other signalling is needed.
"""

from __future__ import annotations

import asyncio
import collections
import logging
import math
import os
import struct
from pathlib import Path
from typing import Any

import numpy as np

log = logging.getLogger(__name__)

FRAME = struct.Struct("=Ifff")
SAMPLES_PER_FRAME = 160  # 10 ms at 16 kHz
FRAME_INTERVAL_S = 0.01
MIN_DBFS = -120.0
MAX_BUFFERED_BYTES = 30 * FRAME.size  # 300 ms: a client further behind than this is dropped
CATCH_UP_FRAMES = 15  # more than this waiting and frames go out two at a time, so lag can't build up
SEQ_MASK = 0xFFFFFFFF


class LevelServer:
    """Runs on the daemon's event loop. Best effort: a slow client is dropped, never waited for."""

    def __init__(self, path: Path):
        self.path = path
        self._server: asyncio.base_events.Server | None = None
        self._clients: set[Any] = set()
        self._pending: collections.deque[bytes] = collections.deque()
        self._leftover = np.zeros(0, dtype=np.float32)
        self._seq = 0
        self._pump: asyncio.Handle | None = None

    async def start(self) -> None:
        self.path.unlink(missing_ok=True)
        old_umask = os.umask(0o177)
        try:
            self._server = await asyncio.start_unix_server(self._serve_client, path=str(self.path))
        finally:
            os.umask(old_umask)

    def close(self) -> None:
        if self._pump is not None:
            self._pump.cancel()
            self._pump = None
        for writer in self._clients:
            writer.close()
        self._clients.clear()
        if self._server is not None:
            self._server.close()
            self._server = None
        self.path.unlink(missing_ok=True)

    async def _serve_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self._clients.add(writer)
        try:
            await reader.read()  # clients never send anything, so this returns when they disconnect
        except ConnectionError:
            pass
        finally:
            self._clients.discard(writer)
            writer.close()

    def new_recording(self) -> None:
        """Skip a sequence number: the visualizer clears its history when frames aren't consecutive."""
        self._pending.clear()
        self._leftover = np.zeros(0, dtype=np.float32)
        self._seq = (self._seq + 1) & SEQ_MASK

    def publish(self, block: np.ndarray) -> None:
        if not self._clients:
            return
        audio = np.concatenate((self._leftover, block))
        whole = len(audio) // SAMPLES_PER_FRAME
        for i in range(whole):
            self._pending.append(self._frame(audio[i * SAMPLES_PER_FRAME : (i + 1) * SAMPLES_PER_FRAME]))
        self._leftover = audio[whole * SAMPLES_PER_FRAME :]
        if self._pending and self._pump is None:
            self._pump = asyncio.get_running_loop().call_soon(self._send_next)

    def _frame(self, window: np.ndarray) -> bytes:
        low = max(-1.0, float(window.min()))
        high = min(1.0, float(window.max()))
        peak = max(abs(low), abs(high))
        dbfs = max(MIN_DBFS, 20.0 * math.log10(peak)) if peak > 0 else MIN_DBFS
        frame = FRAME.pack(self._seq, low, high, dbfs)
        self._seq = (self._seq + 1) & SEQ_MASK
        return frame

    def _send_next(self) -> None:
        """Audio arrives in 100 ms blocks; sending it 10 ms at a time keeps the animation smooth."""
        self._pump = None
        for _ in range(2 if len(self._pending) > CATCH_UP_FRAMES else 1):
            if self._pending:
                self._broadcast(self._pending.popleft())
        if self._pending:
            self._pump = asyncio.get_running_loop().call_later(FRAME_INTERVAL_S, self._send_next)

    def _broadcast(self, frame: bytes) -> None:
        for writer in list(self._clients):
            if writer.is_closing():
                continue
            if writer.transport.get_write_buffer_size() > MAX_BUFFERED_BYTES:
                log.debug("dropping a levels client that fell behind")
                self._clients.discard(writer)
                writer.close()
            else:
                writer.write(frame)
