"""Speech-to-text engines (sherpa-onnx, CPU). Default: NVIDIA Parakeet-TDT 0.6B v3 int8."""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any, Protocol

import numpy as np

from . import paths
from .models import MODELS, model_files

log = logging.getLogger(__name__)

SAMPLE_RATE = 16000


class Transcriber(Protocol):
    name: str

    def transcribe(self, audio: np.ndarray, sample_rate: int = SAMPLE_RATE) -> str: ...


class _SherpaTranscriber:
    name = ""

    def __init__(self) -> None:
        self._rec: Any = None

    def transcribe(self, audio: np.ndarray, sample_rate: int = SAMPLE_RATE) -> str:
        stream = self._rec.create_stream()
        stream.accept_waveform(sample_rate, np.ascontiguousarray(audio, dtype=np.float32))
        self._rec.decode_stream(stream)
        return stream.result.text.strip()

    def warm_up(self) -> None:
        self.transcribe(np.zeros(SAMPLE_RATE, dtype=np.float32))


def _require(files: dict[str, Path], engine: str) -> None:
    missing = [str(p) for p in files.values() if not p.exists()]
    if missing:
        raise FileNotFoundError(f"{engine} model files missing: {', '.join(missing)} — run `murmur download-models`")


def tokenize_hotwords_vocab(tokens_file: Path, out: Path) -> Path:
    """Parakeet ships tokens.txt but no bpe.vocab; sherpa-onnx needs one to encode hotwords.

    Scores favour lower token ids (earlier merges), which approximates the BPE segmentation.
    """
    lines = []
    for line in tokens_file.read_text(encoding="utf-8").splitlines():
        token, _, idx = line.rpartition(" ")
        if token:
            lines.append(f"{token}\t{-float(idx)}")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out


def write_hotwords(terms: list[str], out: Path) -> Path | None:
    clean = sorted({" ".join(t.split()) for t in terms if t.strip()})
    if not clean:
        return None
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(clean) + "\n", encoding="utf-8")
    return out


class ParakeetTranscriber(_SherpaTranscriber):
    name = "parakeet-tdt-0.6b-v3-int8"

    def __init__(self, model_dir: Path, num_threads: int = 4, hotwords: list[str] | None = None):
        super().__init__()
        import sherpa_onnx

        f = model_files(MODELS[self.name], model_dir)
        _require(f, self.name)
        common = dict(
            encoder=str(f["encoder"]),
            decoder=str(f["decoder"]),
            joiner=str(f["joiner"]),
            tokens=str(f["tokens"]),
            num_threads=num_threads,
            sample_rate=SAMPLE_RATE,
            feature_dim=80,
            model_type="nemo_transducer",
            provider="cpu",
        )
        hw_file = write_hotwords(hotwords or [], paths.cache_dir() / "hotwords.txt")
        self.hotwords_active = False
        if hw_file is not None:
            try:
                vocab = tokenize_hotwords_vocab(f["tokens"], paths.cache_dir() / "parakeet-bpe.vocab")
                self._rec = sherpa_onnx.OfflineRecognizer.from_transducer(
                    **common,
                    decoding_method="modified_beam_search",
                    max_active_paths=4,
                    hotwords_file=str(hw_file),
                    hotwords_score=1.5,
                    modeling_unit="bpe",
                    bpe_vocab=str(vocab),
                )
                self.hotwords_active = True
            except Exception as e:
                log.warning("hotwords not supported by this sherpa-onnx build (%s); using greedy search", e)
        if self._rec is None:
            self._rec = sherpa_onnx.OfflineRecognizer.from_transducer(**common, decoding_method="greedy_search")


class MoonshineTranscriber(_SherpaTranscriber):
    name = "moonshine-base"

    def __init__(self, model_dir: Path, num_threads: int = 4, hotwords: list[str] | None = None):
        super().__init__()
        import sherpa_onnx

        f = model_files(MODELS[self.name], model_dir)
        _require(f, self.name)
        self._rec = sherpa_onnx.OfflineRecognizer.from_moonshine(
            preprocessor=str(f["preprocessor"]),
            encoder=str(f["encoder"]),
            uncached_decoder=str(f["uncached_decoder"]),
            cached_decoder=str(f["cached_decoder"]),
            tokens=str(f["tokens"]),
            num_threads=num_threads,
        )


class WhisperTranscriber(_SherpaTranscriber):
    name = "whisper-base.en"

    def __init__(self, model_dir: Path, num_threads: int = 4, hotwords: list[str] | None = None):
        super().__init__()
        import sherpa_onnx

        f = model_files(MODELS[self.name], model_dir)
        _require(f, self.name)
        self._rec = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(f["encoder"]),
            decoder=str(f["decoder"]),
            tokens=str(f["tokens"]),
            language="en",
            task="transcribe",
            num_threads=num_threads,
        )


ENGINES: dict[str, type[_SherpaTranscriber]] = {
    ParakeetTranscriber.name: ParakeetTranscriber,
    MoonshineTranscriber.name: MoonshineTranscriber,
    WhisperTranscriber.name: WhisperTranscriber,
}


def make_transcriber(engine: str, model_dir: Path, num_threads: int = 4, hotwords: list[str] | None = None, warm: bool = True) -> _SherpaTranscriber:
    try:
        cls = ENGINES[engine]
    except KeyError:
        raise ValueError(f"unknown asr.engine {engine!r}; choose one of {', '.join(ENGINES)}") from None
    t = cls(model_dir, num_threads=min(num_threads, os.cpu_count() or num_threads), hotwords=hotwords)
    if warm:
        t.warm_up()
    return t
