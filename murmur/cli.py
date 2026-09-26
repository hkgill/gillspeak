"""`murmur` CLI. Hotkey commands must stay fast: stdlib only at import time (no numpy/onnx)."""

from __future__ import annotations

import argparse
import json
import socket
import sys
from typing import Any

from . import paths

NOT_RUNNING = "murmurd is not running — systemctl --user start murmurd"


def send(request: dict[str, Any], timeout: float = 3.0) -> dict[str, Any]:
    """Send one newline-delimited JSON request; raises FileNotFoundError/ConnectionRefusedError if no daemon."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
        s.settimeout(timeout)
        s.connect(str(paths.socket_path()))
        s.sendall((json.dumps(request) + "\n").encode())
        buf = b""
        while not buf.endswith(b"\n"):
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
    return json.loads(buf or b"{}")


def _ipc(request: dict[str, Any], quiet: bool = True) -> int:
    try:
        resp = send(request)
    except (FileNotFoundError, ConnectionRefusedError):
        print(NOT_RUNNING, file=sys.stderr)
        return 2
    except (OSError, ValueError) as e:
        print(f"murmurd did not answer: {e}", file=sys.stderr)
        return 1
    if not resp.get("ok"):
        print(resp.get("msg") or "failed", file=sys.stderr)
        return 1
    if not quiet:
        msg = resp.get("msg")
        print(resp.get("state", "?") + (f" — {msg}" if msg else ""))
    return 0


def cmd_toggle(a: argparse.Namespace) -> int:
    return _ipc({"cmd": "toggle", "mode": a.mode, "raw": a.raw, "chord": a.chord})


def cmd_history(a: argparse.Namespace) -> int:
    from .config import load
    from .history import History, word_diff

    cfg = load()
    rows = History(paths.history_db(), cfg.history.keep_days).recent(a.n)
    for r in reversed(rows):
        head = f"#{r['id']} {r['created_at']}  mode={r['mode']} llm={r['llm_status']} ({r['gate_reason']})  {r['total_ms']} ms"
        print(head)
        raw, final = r.get("raw_text"), r.get("final_text")
        if raw is None and final is None:
            print("   (text not stored)")
        elif a.diff:
            print("   " + word_diff(raw or "", final or ""))
        else:
            print(f"   raw:   {raw}")
            print(f"   final: {final}")
        print()
    return 0


def cmd_stats(a: argparse.Namespace) -> int:
    from .config import load
    from .history import History, compute_stats

    cfg = load()
    rows = History(paths.history_db(), cfg.history.keep_days).rows_since(a.days)
    st = compute_stats(rows, a.days, cfg.pricing.input_per_m, cfg.pricing.output_per_m)

    def ms(v: float | None) -> str:
        return "—" if v is None else f"{v:.0f} ms"

    print(f"Last {st.days} days")
    print(f"  dictations        {st.dictations}")
    print(f"  words dictated    {st.words}")
    print(f"  LLM calls         {st.llm_calls} ({st.llm_pct:.0f}%)  {st.llm_status}")
    print(f"  latency with LLM  p50 {ms(st.p50_llm_ms)}  p90 {ms(st.p90_llm_ms)}   (target p90 ≤ 1500 ms)")
    print(f"  latency no LLM    p50 {ms(st.p50_nollm_ms)}  p90 {ms(st.p90_nollm_ms)}   (target ≤ 800 ms)")
    print(f"  tokens            {st.input_tokens} in / {st.output_tokens} out")
    print(f"  cost              ${st.cost_window:.3f} in window, ≈ ${st.cost_month_est:.2f}/month")
    return 0


def cmd_set_key(a: argparse.Namespace) -> int:
    from . import secrets

    try:
        if a.proxy:
            where = secrets.set_key(secrets.PROXY_USER, "MURMUR_PROXY_TOKEN")
        else:
            where = secrets.set_key()
    except ValueError as e:
        print(e, file=sys.stderr)
        return 1
    print(f"Stored in {where}. Run `murmur reload` if murmurd is running.")
    return 0


def cmd_download(a: argparse.Namespace) -> int:
    from .config import load
    from .models import ASR_ENGINES, MODELS, download

    cfg = load()
    names = ["silero-vad"] + (ASR_ENGINES if a.all else [a.engine or cfg.asr.engine])
    for name in names:
        if name not in MODELS:
            print(f"unknown engine {name!r}; choose from {', '.join(ASR_ENGINES)}", file=sys.stderr)
            return 1
        download(MODELS[name], cfg.asr.model_path, force=a.force)
    return 0


def cmd_doctor(a: argparse.Namespace) -> int:
    from .doctor import run_doctor

    return run_doctor(skip_llm=a.no_llm, skip_mic=a.no_mic)


def cmd_bench(a: argparse.Namespace) -> int:
    from .bench import run_bench

    return run_bench(a.files, a.engines.split(",") if a.engines else None)


def cmd_eval(a: argparse.Namespace) -> int:
    from .evaluate import run_eval

    return run_eval(a.file, limit=a.limit, verbose=a.verbose)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="murmur", description="Local dictation with LLM clean-up.")
    sub = p.add_subparsers(dest="command", required=True)

    t = sub.add_parser("toggle", help="start/stop dictation")
    t.add_argument("--raw", action="store_true", help="skip the LLM")
    t.add_argument("--mode", default="default", help="tone mode from [modes] (default, formal, casual…)")
    t.add_argument("--chord", choices=["ctrl+v", "ctrl+shift+v"], help="override the paste chord (e.g. terminals)")
    t.set_defaults(func=cmd_toggle)

    sub.add_parser("cancel", help="discard the current recording").set_defaults(func=lambda a: _ipc({"cmd": "cancel"}))
    sub.add_parser("paste-last", help="re-paste the last result").set_defaults(func=lambda a: _ipc({"cmd": "paste_last"}))
    sub.add_parser("status", help="daemon state").set_defaults(func=lambda a: _ipc({"cmd": "status"}, quiet=False))
    sub.add_parser("reload", help="reload config and dictionary").set_defaults(func=lambda a: _ipc({"cmd": "reload"}, quiet=False))

    h = sub.add_parser("history", help="recent raw vs cleaned text")
    h.add_argument("-n", type=int, default=20)
    h.add_argument("--diff", action="store_true", help="show a word diff")
    h.set_defaults(func=cmd_history)

    s = sub.add_parser("stats", help="usage, latency and cost")
    s.add_argument("--days", type=int, default=30)
    s.set_defaults(func=cmd_stats)

    k = sub.add_parser("set-key", help="store the Gemini API key (read from stdin)")
    k.add_argument("--proxy", action="store_true", help="store the v2 proxy bearer token instead")
    k.set_defaults(func=cmd_set_key)

    d = sub.add_parser("download-models", help="fetch ASR + VAD models (SHA256-verified)")
    d.add_argument("--engine", help="ASR engine (default: asr.engine from config)")
    d.add_argument("--all", action="store_true", help="all ASR engines (for `murmur bench`)")
    d.add_argument("--force", action="store_true")
    d.set_defaults(func=cmd_download)

    doc = sub.add_parser("doctor", help="check the environment")
    doc.add_argument("--no-llm", action="store_true", help="skip Gemini checks")
    doc.add_argument("--no-mic", action="store_true", help="skip the 1 s microphone capture")
    doc.set_defaults(func=cmd_doctor)

    b = sub.add_parser("bench", help="benchmark ASR engines on WAV files")
    b.add_argument("files", nargs="+")
    b.add_argument("--engines", help="comma-separated (default: all installed)")
    b.set_defaults(func=cmd_bench)

    e = sub.add_parser("eval", help="run the LLM evaluation set")
    e.add_argument("file", nargs="?", help="JSONL eval set (default: tests/llm_eval.jsonl)")
    e.add_argument("--limit", type=int)
    e.add_argument("-v", "--verbose", action="store_true")
    e.set_defaults(func=cmd_eval)
    return p


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    try:
        code = args.func(args)
    except KeyboardInterrupt:
        code = 130
    except Exception as e:
        if type(e).__name__ == "ConfigError":
            print(f"config error: {e}", file=sys.stderr)
            code = 1
        else:
            raise
    sys.exit(code)


if __name__ == "__main__":
    main()
