"""`murmur bench`: load time, RTF, latency percentiles, RSS and WER per ASR engine."""

from __future__ import annotations

import re
import resource
import time
from pathlib import Path

from .history import percentile


def rss_mb() -> float:
    try:
        for line in Path("/proc/self/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) / 1024
    except OSError:
        pass
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024


def _words(text: str) -> list[str]:
    return re.findall(r"[a-z0-9']+", text.lower())


def wer(ref: str, hyp: str) -> float:
    r, h = _words(ref), _words(hyp)
    if not r:
        return 0.0 if not h else 1.0
    prev = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        cur = [i] + [0] * len(h)
        for j, hw in enumerate(h, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rw != hw))
        prev = cur
    return prev[-1] / len(r)


def run_bench(files: list[str], engines: list[str] | None = None) -> int:
    import subprocess
    import sys

    from .asr import ENGINES, make_transcriber
    from .audio import SAMPLE_RATE, read_wav
    from .config import load
    from .models import MODELS, model_files

    cfg = load()
    if engines is None:
        engines = [e for e in ENGINES if all(p.exists() for p in model_files(MODELS[e], cfg.asr.model_path).values())]
    if len(engines) > 1:
        # One process per engine, so load time and RSS aren't skewed by the previous model.
        for engine in engines:
            subprocess.run([sys.executable, "-m", "murmur.cli", "bench", "--engines", engine, *files], check=False)
        return 0
    clips = [(Path(f), read_wav(Path(f))) for f in files]
    total_audio = sum(len(a) for _, a in clips) / SAMPLE_RATE
    print(f"{len(clips)} clips, {total_audio:.1f} s of audio, {cfg.asr.num_threads} threads\n")
    for engine in engines:
        base = rss_mb()
        t0 = time.monotonic()
        try:
            t = make_transcriber(engine, cfg.asr.model_path, cfg.asr.num_threads, warm=True)
        except Exception as e:
            print(f"{engine}: failed to load: {e}\n")
            continue
        load_s = time.monotonic() - t0
        lat, errs = [], []
        proc_s = 0.0
        for path, audio in clips:
            t1 = time.monotonic()
            text = t.transcribe(audio)
            dt = time.monotonic() - t1
            proc_s += dt
            lat.append(dt * 1000)
            ref_file = path.with_suffix(".txt")
            w = wer(ref_file.read_text(), text) if ref_file.exists() else None
            if w is not None:
                errs.append(w)
            print(f"  {path.name}: {dt * 1000:6.0f} ms  {'' if w is None else f'WER {w:.0%}  '}{text}")
        print(f"{engine}")
        print(f"  load {load_s:.1f} s   RTF {proc_s / max(total_audio, 1e-9):.3f}   "
              f"p50 {percentile(lat, 50):.0f} ms   p90 {percentile(lat, 90):.0f} ms   "
              f"RSS +{rss_mb() - base:.0f} MB (total {rss_mb():.0f} MB)"
              + (f"   WER {sum(errs) / len(errs):.1%}" if errs else ""))
        print()
    return 0
