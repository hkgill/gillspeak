import threading

import numpy as np

from murmur.audio import BLOCK_SIZE, Recorder, read_wav, write_wav


class FakeStream:
    def __init__(self, callback):
        self.callback = callback
        self.active = False
        self.closed = False

    def start(self):
        self.active = True

    def stop(self):
        self.active = False

    def close(self):
        self.closed = True

    def feed(self, value, blocks=1):
        for _ in range(blocks):
            self.callback(np.full((BLOCK_SIZE, 1), value, dtype=np.float32), BLOCK_SIZE, None, None)


def make(**kw):
    streams = []

    def factory(callback):
        s = FakeStream(callback)
        streams.append(s)
        return s

    rec = Recorder(stream_factory=factory, **kw)
    return rec, streams


def test_preroll_included():
    rec, streams = make()
    rec.start_idle()
    s = streams[0]
    s.feed(0.1, blocks=10)  # idle audio; only the last 300 ms (3 blocks) are kept
    rec.start()
    s.feed(0.5, blocks=5)
    audio = rec.stop()
    assert len(audio) == 8 * BLOCK_SIZE
    assert np.allclose(audio[: 3 * BLOCK_SIZE], 0.1)
    assert np.allclose(audio[3 * BLOCK_SIZE :], 0.5)
    assert not rec.is_recording


def test_cancel_discards():
    rec, streams = make()
    rec.start_idle()
    rec.start()
    streams[0].feed(0.5, blocks=3)
    rec.cancel()
    assert not rec.is_recording
    rec.start()
    assert len(rec.stop()) <= 3 * BLOCK_SIZE  # only fresh pre-roll, nothing from the cancelled take


def test_max_seconds_fires_once():
    fired = threading.Event()
    calls = []
    rec, streams = make(max_seconds=0.5, on_max=lambda: (calls.append(1), fired.set()))
    rec.start_idle()
    rec.start()
    streams[0].feed(0.2, blocks=10)
    assert fired.is_set() and len(calls) == 1


def test_keep_mic_open_false_closes_after_stop():
    rec, streams = make(keep_open=False)
    rec.start_idle()
    assert streams == []
    rec.start()
    assert len(streams) == 1 and streams[0].active
    streams[0].feed(0.1, blocks=2)
    rec.stop()
    assert streams[0].closed


def test_dead_stream_is_reopened():
    rec, streams = make()
    rec.start_idle()
    streams[0].active = False  # e.g. device vanished during suspend
    rec.start()
    assert len(streams) == 2 and streams[1].active
    assert streams[0].closed


def test_wav_roundtrip_and_resample(tmp_path):
    audio = (0.5 * np.sin(np.linspace(0, 100, 16000))).astype(np.float32)
    write_wav(tmp_path / "a.wav", audio, 16000)
    back = read_wav(tmp_path / "a.wav")
    assert len(back) == 16000 and np.max(np.abs(back - audio)) < 1e-3
    write_wav(tmp_path / "b.wav", audio, 8000)
    assert len(read_wav(tmp_path / "b.wav")) == 32000


def test_stream_that_fails_to_start_is_closed():
    """Regression (Codex review #10): if start() failed, the constructed stream was never closed,
    so every retry leaked another PortAudio stream."""
    import pytest

    from murmur.audio import RecorderError

    streams = []

    class Broken(FakeStream):
        def start(self):
            raise OSError("device unavailable")

    def factory(callback):
        s = Broken(callback)
        streams.append(s)
        return s

    rec = Recorder(stream_factory=factory, keep_open=False)
    for _ in range(3):
        with pytest.raises(RecorderError):
            rec.start()
    assert len(streams) == 3 and all(s.closed for s in streams)
