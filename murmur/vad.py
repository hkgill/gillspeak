"""Voice activity detection: trim silence, reject empty clips, plan chunks for long recordings."""

from __future__ import annotations

import itertools
import logging
import threading
from pathlib import Path
from typing import Protocol

import numpy as np

log = logging.getLogger(__name__)

SAMPLE_RATE = 16000
PRE_PAD_S = 0.15
POST_PAD_S = 0.25
LONG_AUDIO_S = 60.0
MAX_CHUNK_S = 30.0

Segment = tuple[int, int]  # (start_sample, end_sample), end exclusive


class Vad(Protocol):
    def segments(self, audio: np.ndarray) -> list[Segment]: ...


class SileroVad:
    """Silero VAD through sherpa-onnx (silero_vad.onnx)."""

    def __init__(self, model_path: Path, threshold: float = 0.5):
        import sherpa_onnx

        cfg = sherpa_onnx.VadModelConfig()
        cfg.silero_vad.model = str(model_path)
        cfg.silero_vad.threshold = threshold
        cfg.silero_vad.min_silence_duration = 0.3
        cfg.silero_vad.min_speech_duration = 0.1
        cfg.silero_vad.max_speech_duration = MAX_CHUNK_S
        cfg.sample_rate = SAMPLE_RATE
        cfg.num_threads = 1
        self._window = cfg.silero_vad.window_size
        self._vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=int(LONG_AUDIO_S * 10))
        self._lock = threading.Lock()

    def segments(self, audio: np.ndarray) -> list[Segment]:
        out: list[Segment] = []
        with self._lock:
            self._vad.reset()
            w = self._window
            for i in range(0, len(audio), w):
                self._vad.accept_waveform(audio[i : i + w])
            self._vad.flush()
            while not self._vad.empty():
                seg = self._vad.front
                out.append((seg.start, seg.start + len(seg.samples)))
                self._vad.pop()
        return out


class EnergyVad:
    """Fallback when the Silero model is missing: frame RMS against an adaptive noise floor."""

    def __init__(self, frame_s: float = 0.03, min_rms: float = 0.01, hangover_s: float = 0.3):
        self.frame = int(frame_s * SAMPLE_RATE)
        self.min_rms = min_rms
        self.hangover = max(1, int(hangover_s / frame_s))

    def segments(self, audio: np.ndarray) -> list[Segment]:
        n = len(audio) // self.frame
        if n == 0:
            return []
        frames = audio[: n * self.frame].reshape(n, self.frame)
        rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
        floor = float(np.percentile(rms, 20))
        voiced = rms > max(self.min_rms, 3.0 * floor)
        out: list[Segment] = []
        start, silent = None, 0
        for i, v in enumerate(voiced):
            if v:
                if start is None:
                    start = i
                silent = 0
            elif start is not None:
                silent += 1
                if silent > self.hangover:
                    out.append((start * self.frame, (i - silent + 1) * self.frame))
                    start, silent = None, 0
        if start is not None:
            out.append((start * self.frame, (n - silent) * self.frame))
        return out


def speech_seconds(segments: list[Segment], sample_rate: int = SAMPLE_RATE) -> float:
    return sum(e - s for s, e in segments) / sample_rate


def trim(audio: np.ndarray, vad: Vad, min_speech_s: float = 0.4, sample_rate: int = SAMPLE_RATE) -> np.ndarray | None:
    """Audio from first onset - 150 ms to last offset + 250 ms, or None if < min_speech_s of speech."""
    segs = vad.segments(audio)
    return trim_to_segments(audio, segs, min_speech_s, sample_rate)


def trim_to_segments(
    audio: np.ndarray, segs: list[Segment], min_speech_s: float = 0.4, sample_rate: int = SAMPLE_RATE
) -> np.ndarray | None:
    if not segs or speech_seconds(segs, sample_rate) < min_speech_s:
        return None
    start = max(0, segs[0][0] - int(PRE_PAD_S * sample_rate))
    end = min(len(audio), segs[-1][1] + int(POST_PAD_S * sample_rate))
    return audio[start:end]


def plan_chunks(
    segs: list[Segment], total: int, sample_rate: int = SAMPLE_RATE, max_chunk_s: float = MAX_CHUNK_S
) -> list[Segment]:
    """Split [0, total) into <= max_chunk_s pieces, cutting in the middle of VAD pauses."""
    max_len = int(max_chunk_s * sample_rate)
    if total <= max_len:
        return [(0, total)]
    # Candidate cut points: midpoints of the gaps between speech segments.
    cuts = sorted((a_end + b_start) // 2 for (_, a_end), (b_start, _) in itertools.pairwise(segs) if b_start > a_end)
    chunks: list[Segment] = []
    start = 0
    while total - start > max_len:
        limit = start + max_len
        usable = [c for c in cuts if start < c <= limit]
        cut = usable[-1] if usable else limit  # no pause in range: hard cut
        chunks.append((start, cut))
        start = cut
    chunks.append((start, total))
    return chunks


def split_long(audio: np.ndarray, vad: Vad, sample_rate: int = SAMPLE_RATE) -> list[np.ndarray]:
    """For > 60 s of (trimmed) audio, return <= 30 s chunks split at pauses; otherwise [audio]."""
    if len(audio) <= LONG_AUDIO_S * sample_rate:
        return [audio]
    return [audio[s:e] for s, e in plan_chunks(vad.segments(audio), len(audio), sample_rate)]


def make_vad(engine: str, model_dir: Path) -> Vad:
    if engine == "silero":
        path = model_dir / "silero_vad.onnx"
        if path.exists():
            return SileroVad(path)
        log.warning("%s missing (run `murmur download-models`); using energy VAD", path)
    return EnergyVad()
