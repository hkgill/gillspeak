"""Microphone capture: 16 kHz mono float32 with a 300 ms pre-roll so first syllables aren't clipped."""

from __future__ import annotations

import collections
import logging
import threading
import time
import wave
from collections.abc import Callable
from pathlib import Path
from typing import Any

import numpy as np

log = logging.getLogger(__name__)

SAMPLE_RATE = 16000
BLOCK_SIZE = 1600  # 100 ms
PREROLL_S = 0.3
STALL_S = 2.0  # no callbacks for this long => stream is dead (suspend, PipeWire restart)


class RecorderError(Exception):
    pass


def _sd() -> Any:
    try:
        import sounddevice
    except OSError as e:  # PortAudio missing
        raise RecorderError(f"PortAudio not available: {e}") from e
    return sounddevice


def find_input_device(name: str) -> int | None:
    """Resolve config `audio.device` (a name substring) to a PortAudio index; None = default."""
    if not name or name == "default":
        return None
    sd = _sd()
    for idx, dev in enumerate(sd.query_devices()):
        if dev.get("max_input_channels", 0) > 0 and name.lower() in dev["name"].lower():
            return idx
    raise RecorderError(f"no input device matching {name!r}")


class Recorder:
    def __init__(
        self,
        device: str = "default",
        *,
        keep_open: bool = True,
        max_seconds: float = 300.0,
        on_max: Callable[[], None] | None = None,
        stream_factory: Callable[..., Any] | None = None,
    ):
        self.device = device
        self.keep_open = keep_open
        self.max_samples = int(max_seconds * SAMPLE_RATE)
        self.on_max = on_max
        self._stream_factory = stream_factory
        self._stream: Any = None
        self._lock = threading.Lock()
        self._preroll: collections.deque[np.ndarray] = collections.deque(
            maxlen=max(1, round(PREROLL_S * SAMPLE_RATE / BLOCK_SIZE))
        )
        self._chunks: list[np.ndarray] = []
        self._n = 0
        self._recording = False
        self._max_fired = False
        self._last_cb = 0.0

    # -- stream management -------------------------------------------------
    def _make_stream(self) -> Any:
        if self._stream_factory:
            return self._stream_factory(callback=self._callback)
        sd = _sd()
        return sd.InputStream(
            samplerate=SAMPLE_RATE,
            channels=1,
            dtype="float32",
            blocksize=BLOCK_SIZE,
            device=find_input_device(self.device),
            callback=self._callback,
        )

    def open(self) -> None:
        if self._stream is not None:
            return
        try:
            stream = self._make_stream()
            stream.start()
        except RecorderError:
            raise
        except Exception as e:
            raise RecorderError(f"cannot open microphone: {e}") from e
        self._stream = stream
        self._last_cb = time.monotonic()

    def close(self) -> None:
        stream, self._stream = self._stream, None
        if stream is not None:
            try:
                stream.stop()
                stream.close()
            except Exception as e:
                log.debug("closing stream: %s", e)

    def _healthy(self) -> bool:
        s = self._stream
        if s is None or not getattr(s, "active", True):
            return False
        return time.monotonic() - self._last_cb < STALL_S

    def reopen(self) -> None:
        """Recover from device loss (suspend/resume, PipeWire restart): refresh PortAudio and reopen."""
        self.close()
        if self._stream_factory is None:
            try:
                sd = _sd()
                sd._terminate()
                sd._initialize()
            except Exception as e:
                log.debug("PortAudio reinit: %s", e)
        self.open()

    def ensure_open(self) -> None:
        if self._stream is None:
            self.open()
        elif not self._healthy():
            log.info("audio stream unhealthy; reopening")
            self.reopen()

    # -- recording ---------------------------------------------------------
    def _callback(self, indata: np.ndarray, frames: int, time_info: Any, status: Any) -> None:
        if status:
            log.debug("audio status: %s", status)
        self._last_cb = time.monotonic()
        block = np.array(indata[:, 0] if indata.ndim == 2 else indata, dtype=np.float32, copy=True)
        fire = False
        with self._lock:
            if self._recording:
                self._chunks.append(block)
                self._n += len(block)
                if self._n >= self.max_samples and not self._max_fired:
                    self._max_fired = fire = True
            else:
                self._preroll.append(block)
        if fire and self.on_max:
            self.on_max()

    @property
    def is_recording(self) -> bool:
        return self._recording

    def start(self) -> None:
        self.ensure_open()
        with self._lock:
            self._chunks = list(self._preroll)
            self._n = sum(len(c) for c in self._chunks)
            self._preroll.clear()
            self._max_fired = False
            self._recording = True

    def _finish(self) -> np.ndarray:
        with self._lock:
            self._recording = False
            chunks, self._chunks, self._n = self._chunks, [], 0
        if not self.keep_open:
            self.close()
        return np.concatenate(chunks) if chunks else np.zeros(0, dtype=np.float32)

    def stop(self) -> np.ndarray:
        return self._finish()

    def cancel(self) -> None:
        self._finish()

    def start_idle(self) -> None:
        """Called at daemon start: with keep_mic_open the stream stays open to fill the pre-roll."""
        if self.keep_open:
            self.open()


def write_wav(path: Path, audio: np.ndarray, sample_rate: int = SAMPLE_RATE) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm.tobytes())


def read_wav(path: Path, target_rate: int = SAMPLE_RATE) -> np.ndarray:
    """Read a PCM WAV as mono float32 at target_rate (linear resample if needed)."""
    with wave.open(str(path), "rb") as w:
        rate, width, channels = w.getframerate(), w.getsampwidth(), w.getnchannels()
        raw = w.readframes(w.getnframes())
    if width == 2:
        audio = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif width == 4:
        audio = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    elif width == 1:
        audio = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    else:
        raise ValueError(f"{path}: unsupported sample width {width}")
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)
    if rate != target_rate and len(audio):
        n = round(len(audio) * target_rate / rate)
        audio = np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)
    return audio
