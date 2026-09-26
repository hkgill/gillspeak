"""Regenerate murmur/assets/sounds/*.wav (short, quiet sine blips)."""

import math
import struct
import wave
from pathlib import Path

RATE = 22050
OUT = Path(__file__).resolve().parent.parent / "murmur" / "assets" / "sounds"


def tone(freqs: list[tuple[float, float]], volume: float = 0.25) -> bytes:
    frames = bytearray()
    for freq, dur in freqs:
        n = int(RATE * dur)
        for i in range(n):
            env = min(1.0, i / (0.005 * RATE), (n - i) / (0.02 * RATE))  # 5 ms attack, 20 ms release
            frames += struct.pack("<h", int(32767 * volume * env * math.sin(2 * math.pi * freq * i / RATE)))
    return bytes(frames)


SOUNDS = {
    "start": [(660, 0.06), (880, 0.08)],
    "stop": [(880, 0.06), (660, 0.08)],
    "error": [(330, 0.12), (0, 0.04), (330, 0.12)],
}

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, spec in SOUNDS.items():
        with wave.open(str(OUT / f"{name}.wav"), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(tone(spec))
        print(OUT / f"{name}.wav")
