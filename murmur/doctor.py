"""`murmur doctor`: environment checks with a fix hint for each failure."""

from __future__ import annotations

import os
import shutil
import subprocess
import time
from typing import Callable

from . import paths

OK, FAIL, WARN = "✅", "❌", "⚠️ "


class Report:
    def __init__(self) -> None:
        self.failures = 0

    def line(self, status: str, name: str, detail: str = "", hint: str = "") -> None:
        print(f"{status} {name}" + (f": {detail}" if detail else ""))
        if hint and status != OK:
            print(f"     → {hint}")
        if status == FAIL:
            self.failures += 1

    def check(self, name: str, fn: Callable[[], tuple[bool, str]], hint: str) -> bool:
        try:
            ok, detail = fn()
        except Exception as e:
            ok, detail = False, f"{type(e).__name__}: {e}"
        self.line(OK if ok else FAIL, name, detail, hint)
        return ok


def _run(argv: list[str], stdin: bytes | None = None, timeout: float = 3.0) -> subprocess.CompletedProcess[bytes]:
    if stdin is not None:  # wl-copy forks a clipboard server; don't hold its pipes
        return subprocess.run(argv, input=stdin, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout)
    return subprocess.run(argv, capture_output=True, timeout=timeout)


def run_doctor(skip_llm: bool = False, skip_mic: bool = False) -> int:
    from . import config as config_mod
    from .inject import session_type, ydotool_socket
    from .models import MODELS, verify

    r = Report()
    try:
        cfg = config_mod.load()
        config_mod.load_dictionary()
        r.line(OK, "config", str(paths.config_file()))
    except config_mod.ConfigError as e:
        r.line(FAIL, "config", str(e), f"fix {paths.config_file()}")
        return 1

    # Session + tools
    st = session_type()
    r.line(OK, "session", st)
    if st == "wayland":
        for tool, pkg in (("wl-copy", "wl-clipboard"), ("wl-paste", "wl-clipboard"), ("ydotool", "ydotool")):
            r.line(OK if shutil.which(tool) else FAIL, f"{tool} installed", hint=f"sudo dnf install {pkg}")
        if shutil.which("wl-copy"):
            probe = f"murmur-doctor-{os.getpid()}"

            def clip() -> tuple[bool, str]:
                _run(["wl-copy", "--type", "text/plain"], stdin=probe.encode())
                out = _run(["wl-paste", "--no-newline"]).stdout.decode(errors="replace")
                return out == probe, "round-trip ok" if out == probe else f"read back {out[:40]!r}"

            r.check("wl-copy/wl-paste work", clip, "Are you in a Wayland session? Is WAYLAND_DISPLAY set for the service?")
        sock = ydotool_socket()

        def ydo() -> tuple[bool, str]:
            if not sock.exists():
                return False, f"{sock} missing"
            if not os.access(sock, os.W_OK):
                return False, f"{sock} not writable by you"
            p = subprocess.run(["ydotool", "key", "42:1", "42:0"], capture_output=True, timeout=3,
                               env={**os.environ, "YDOTOOL_SOCKET": str(sock)})  # tap Shift: a no-op
            err = p.stderr.decode(errors="replace").strip()
            return p.returncode == 0 and "failed" not in err.lower(), err or f"socket {sock}"

        r.check("ydotool reachable", ydo, "sudo systemctl enable --now ydotool; set YDOTOOL_SOCKET in murmurd.service (see scripts/setup-fedora.sh)")
    else:
        for tool in ("xclip", "xdotool"):
            r.line(OK if shutil.which(tool) else FAIL, f"{tool} installed", hint=f"sudo dnf install {tool}")

    for tool, pkg in (("notify-send", "libnotify"), ("pw-play", "pipewire-utils")):
        r.line(OK if shutil.which(tool) else WARN, f"{tool} installed", hint=f"sudo dnf install {pkg} (optional)")

    # Daemon
    from .cli import send

    try:
        resp = send({"cmd": "status"}, timeout=1.0)
        r.line(OK, "murmurd running", f"{resp.get('state')} {resp.get('msg') or ''}".strip())
    except OSError:
        r.line(WARN, "murmurd running", "not running", "systemctl --user enable --now murmurd")

    # Mic
    if not skip_mic:
        def mic() -> tuple[bool, str]:
            import numpy as np
            import sounddevice as sd

            from .audio import SAMPLE_RATE, find_input_device

            dev = find_input_device(cfg.audio.device)
            data = sd.rec(SAMPLE_RATE, samplerate=SAMPLE_RATE, channels=1, dtype="float32", device=dev, blocking=True)
            rms = float(np.sqrt(np.mean(np.square(data))))
            name = sd.query_devices(dev, "input")["name"]
            return rms > 1e-4, f"{name}, RMS {rms:.5f}"

        r.check("microphone delivers audio", mic, "Check Settings → Sound → Input, or set audio.device in config.toml")

    # Models
    model_dir = cfg.asr.model_path
    for name in (cfg.asr.engine, "silero-vad"):
        spec = MODELS.get(name)
        if spec is None:
            r.line(FAIL, f"model {name}", "unknown engine", "set asr.engine to a supported engine")
            continue
        ok, detail = verify(spec, model_dir)
        r.line(OK if ok else FAIL, f"model {name}", detail, "murmur download-models")

    # LLM
    if cfg.llm.provider == "gemini" and not skip_llm:
        _check_gemini(r, cfg)
    elif cfg.llm.provider == "none":
        r.line(OK, "LLM", "disabled (provider = none)")

    print()
    print("All good." if r.failures == 0 else f"{r.failures} problem(s) found.")
    return 0 if r.failures == 0 else 1


def _check_gemini(r: Report, cfg: object) -> None:
    import asyncio

    import httpx

    from . import secrets
    from .cleaner import GEMINI_BASE, CleanerError, GeminiCleaner

    key = secrets.get_api_key()
    if not key:
        r.line(FAIL, "Gemini API key", "not found", "murmur set-key")
        return
    r.line(OK, "Gemini API key", "found (keyring or env)")
    try:
        resp = httpx.get(f"{GEMINI_BASE}/models", params={"pageSize": 1000}, headers={"x-goog-api-key": key}, timeout=10)
    except httpx.HTTPError as e:
        r.line(FAIL, "models.list", str(e), "check network")
        return
    if resp.status_code != 200:
        r.line(FAIL, "models.list", f"HTTP {resp.status_code}", "check the key is valid and the Generative Language API is enabled")
        return
    names = [m["name"].removeprefix("models/") for m in resp.json().get("models", [])]
    lite = sorted(n for n in names if "flash-lite" in n)
    r.line(OK, "models.list", f"{len(names)} models; Flash-Lite: {', '.join(lite) or 'none'}")
    model = cfg.llm.model  # type: ignore[attr-defined]
    if model in names:
        r.line(OK, "configured model", model)
        if model.endswith("latest"):
            r.line(WARN, "model pinning", f"{model} is an alias", "pin a versioned ID in llm.model for stable behaviour")
    else:
        r.line(FAIL, "configured model", f"{model} not available", f"set llm.model to one of: {', '.join(lite)}")
        return

    async def one() -> tuple[bool, str]:
        cleaner = GeminiCleaner(cfg, key)  # type: ignore[arg-type]
        try:
            await cleaner.warm()
            t0 = time.monotonic()
            res = await cleaner.clean("um so the meeting is on thursday sorry friday at 3 pm", mode="default",
                                      instructions=cfg.llm.instructions, bias_terms=[])  # type: ignore[attr-defined]
            return True, f"{(time.monotonic() - t0) * 1000:.0f} ms → {res.text!r} ({res.input_tokens}/{res.output_tokens} tokens)"
        except CleanerError as e:
            return False, f"{e.kind}: {e}"
        finally:
            await cleaner.aclose()

    r.check("test generateContent", lambda: asyncio.run(one()),
            "check llm.extra_generation_config (thinking settings) against the Gemini docs for this model")
