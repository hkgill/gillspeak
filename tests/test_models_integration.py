"""Runs the real Silero VAD + Parakeet models. Opt-in: MURMUR_TEST_MODELS=<model_dir> pytest."""

import os
from pathlib import Path

import numpy as np
import pytest

MODEL_DIR = os.environ.get("MURMUR_TEST_MODELS")
pytestmark = pytest.mark.skipif(not MODEL_DIR, reason="set MURMUR_TEST_MODELS to a directory with downloaded models")


@pytest.fixture(scope="module")
def model_dir():
    return Path(MODEL_DIR).expanduser()


@pytest.fixture(scope="module")
def en_wav(model_dir):
    from murmur.audio import read_wav

    return read_wav(model_dir / "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" / "test_wavs" / "en.wav")


def test_checksums(model_dir):
    from murmur.models import MODELS, load_manifest, verify

    if not load_manifest(model_dir):
        pytest.skip("models not installed via `murmur download-models`")
    for name in ("parakeet-tdt-0.6b-v3-int8", "silero-vad"):
        assert verify(MODELS[name], model_dir) == (True, "ok")


def test_silero_trim(model_dir, en_wav):
    from murmur.vad import SileroVad, trim

    vad = SileroVad(model_dir / "silero_vad.onnx")
    padded = np.concatenate([np.zeros(16000 * 2, np.float32), en_wav, np.zeros(16000 * 2, np.float32)])
    out = trim(padded, vad)
    assert out is not None
    assert len(en_wav) * 0.8 < len(out) < len(en_wav) + 16000
    assert trim(np.zeros(16000 * 3, np.float32), vad) is None
    # reusable across calls
    assert trim(padded, vad) is not None


@pytest.mark.parametrize("hotwords", [None, ["Supabase", "Teltonika"]])
def test_parakeet(model_dir, en_wav, hotwords):
    from murmur.asr import make_transcriber

    t = make_transcriber("parakeet-tdt-0.6b-v3-int8", model_dir, 4, hotwords)
    assert getattr(t, "hotwords_active", False) is bool(hotwords)
    text = t.transcribe(en_wav)
    # Punctuation can vary with resampling; the words must not.
    words = [w.strip(",.").lower() for w in text.split()]
    assert words == ["ask", "not", "what", "your", "country", "can", "do", "for", "you", "ask", "what", "you", "can", "do", "for", "your", "country"]
    assert text[0].isupper() and text.endswith(".")


async def test_daemon_end_to_end(model_dir, en_wav, tmp_path):
    """Real VAD + Parakeet + rules through the daemon; recorder and injector faked."""
    from murmur.asr import make_transcriber
    from murmur.config import Config, Dictionary
    from murmur.daemon import Daemon
    from murmur.history import History
    from murmur.vad import SileroVad
    from tests.test_ipc import FakeInjector, FakeNotifier, FakeRecorder

    cfg = Config()
    cfg.llm.provider = "none"
    rec = FakeRecorder()
    rec.audio = np.concatenate([np.zeros(8000, np.float32), en_wav, np.zeros(16000, np.float32)])
    d = Daemon(
        cfg,
        Dictionary(replace={"your country": "your nation"}),
        recorder=rec,
        transcriber=make_transcriber("parakeet-tdt-0.6b-v3-int8", model_dir, 4),
        vad=SileroVad(model_dir / "silero_vad.onnx"),
        injector=FakeInjector(),
        history=History(tmp_path / "h.db"),
        notifier=FakeNotifier(),
    )
    d.ready.set()
    await d.handle({"cmd": "toggle"})
    await d.handle({"cmd": "toggle"})
    await d.process(d.queue.get_nowait())
    text = d.injector.inserted[0][0]
    assert "your nation" in text and "country" not in text
    row = d.history.recent(1)[0]
    assert row["gate_reason"] == "provider_none" and row["asr_ms"] > 0
