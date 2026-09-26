import numpy as np

from murmur.vad import EnergyVad, plan_chunks, split_long, trim, trim_to_segments

SR = 16000


def tone(seconds, amp=0.3, freq=220.0):
    t = np.arange(int(seconds * SR)) / SR
    return (amp * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def silence(seconds):
    return np.zeros(int(seconds * SR), dtype=np.float32)


def test_trim_pads_around_speech():
    audio = np.zeros(SR * 5, dtype=np.float32)
    out = trim_to_segments(audio, [(SR * 1, SR * 2), (SR * 3, SR * 4)])
    assert len(out) == (SR * 4 + int(0.25 * SR)) - (SR * 1 - int(0.15 * SR))


def test_trim_clamps_to_bounds():
    audio = np.zeros(SR, dtype=np.float32)
    assert len(trim_to_segments(audio, [(0, SR)])) == SR


def test_trim_rejects_short_speech():
    audio = np.zeros(SR * 2, dtype=np.float32)
    assert trim_to_segments(audio, [(0, int(0.3 * SR))], min_speech_s=0.4) is None
    assert trim_to_segments(audio, [], min_speech_s=0.4) is None


def test_energy_vad_finds_tone():
    audio = np.concatenate([silence(1), tone(1.5), silence(1)])
    segs = EnergyVad().segments(audio)
    assert len(segs) == 1
    start, end = segs[0]
    assert abs(start / SR - 1.0) < 0.05
    assert abs(end / SR - 2.5) < 0.05
    out = trim(audio, EnergyVad())
    assert out is not None and 1.5 < len(out) / SR < 2.0


def test_energy_vad_double_tap_discarded():
    audio = np.concatenate([silence(0.5), tone(0.2), silence(0.5)])
    assert trim(audio, EnergyVad(), min_speech_s=0.4) is None


def test_plan_chunks_short_is_single():
    assert plan_chunks([], SR * 20) == [(0, SR * 20)]


def test_plan_chunks_cuts_in_pauses():
    # speech 0-20s, pause 20-21s, speech 21-45s, pause 45-46, speech 46-70
    segs = [(0, 20 * SR), (21 * SR, 45 * SR), (46 * SR, 70 * SR)]
    chunks = plan_chunks(segs, 70 * SR)
    assert chunks[0] == (0, int(20.5 * SR))
    assert chunks[1] == (int(20.5 * SR), int(45.5 * SR))
    assert chunks[-1][1] == 70 * SR
    assert all(e - s <= 30 * SR for s, e in chunks)


def test_plan_chunks_hard_cut_without_pauses():
    chunks = plan_chunks([(0, 75 * SR)], 75 * SR)
    assert chunks == [(0, 30 * SR), (30 * SR, 60 * SR), (60 * SR, 75 * SR)]


def test_split_long_only_over_60s():
    class NoVad:
        def segments(self, audio):
            return []

    assert len(split_long(np.zeros(50 * SR, dtype=np.float32), NoVad())) == 1
    parts = split_long(np.zeros(65 * SR, dtype=np.float32), NoVad())
    assert [len(p) for p in parts] == [30 * SR, 30 * SR, 5 * SR]
