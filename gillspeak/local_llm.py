"""Local clean-up (llm.provider = "local"): a small model served by llama.cpp's llama-server, on this computer only.

The server listens on 127.0.0.1 with a random port and a per-run API key. It's started when the daemon starts or a
recording begins, and stopped after llm.local_idle_unload_s idle seconds to give back its ~2 GB of RAM.

Reading the ~800-token system prompt takes ~10 s on an older laptop CPU, so the model's state right after the prompt
is saved to disk once and restored (in milliseconds) on every later start. The state has to end exactly where the
prompt does: hybrid models such as Qwen3.5 can't rewind a longer saved state to a shorter prefix.
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import logging
import os
import secrets
import socket
import time
from pathlib import Path
from typing import Any

import httpx

from . import paths
from .cleaner import CleanerError, CleanResult, build_payload, estimate_tokens, strip_echo, system_prompt
from .config import Config
from .models import LLM_MODELS, llama_server_spec, model_files, verify

log = logging.getLogger(__name__)

PROMPT = "clean_local"
USER_MARK = "\x00GILLSPEAK_USER\x00"
START_TIMEOUT_S = 60.0
IDLE_CHECK_S = 30.0
CONTEXT = 4096


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def default_threads() -> int:
    return max(2, (os.cpu_count() or 4) // 2)


def missing_files(cfg: Config, *, checksums: bool = False) -> str | None:
    """Why local clean-up can't run yet, or None when the server and model are installed. Checking the
    checksums reads the whole 1.3 GB model (seconds), so only `gillspeak doctor` does it."""
    server = llama_server_spec()
    if server is None:
        import platform

        return f"no prebuilt llama-server for {platform.machine()}"
    model_dir = cfg.asr.model_path
    for spec in (server, LLM_MODELS[cfg.llm.local_model]):
        if checksums:
            ok, detail = verify(spec, model_dir)
            if not ok:
                return f"{spec.name}: {detail}"
        else:
            for path in model_files(spec, model_dir).values():
                if not path.exists():
                    return f"{spec.name}: missing {path}"
    return None


class LocalCleaner:
    prompt_ver = PROMPT

    def __init__(self, cfg: Config, client: httpx.AsyncClient | None = None):
        self.cfg = cfg
        self.model = cfg.llm.local_model
        spec = LLM_MODELS[cfg.llm.local_model]
        server = llama_server_spec()
        if server is None:
            raise CleanerError("error", missing_files(cfg) or "no llama-server")
        self._server = model_files(server, cfg.asr.model_path)["server"]
        self._gguf = model_files(spec, cfg.asr.model_path)["model"]
        self._prompt = system_prompt(PROMPT)
        self._client = client or httpx.AsyncClient(timeout=httpx.Timeout(START_TIMEOUT_S, connect=1.0))
        self._proc: asyncio.subprocess.Process | None = None
        self._base = ""
        self._key = ""
        self._prefix = ""
        self._suffix = ""
        self._ready: asyncio.Task[None] | None = None
        self._idle_task: asyncio.Task[None] | None = None
        self._last_used = time.monotonic()
        self._lock = asyncio.Lock()

    # Lifecycle

    def needs_warm(self) -> bool:
        if self._ready is None:
            return True
        if not self._ready.done():
            return False
        failed = self._ready.cancelled() or self._ready.exception() is not None
        return failed or self._proc is None or self._proc.returncode is not None  # e.g. it crashed

    async def warm(self) -> None:
        """Start the server (if it isn't running) without waiting for it."""
        self._last_used = time.monotonic()
        if self.needs_warm():
            self._ready = asyncio.create_task(self._start())
            self._ready.add_done_callback(_log_start_failure)
        if self._idle_task is None or self._idle_task.done():
            self._idle_task = asyncio.create_task(self._idle_loop())

    async def wait_ready(self) -> None:
        await self.warm()
        assert self._ready is not None
        await self._ready

    async def _start(self) -> None:
        async with self._lock:
            if self._proc is not None and self._proc.returncode is None:
                return
            t0 = time.monotonic()
            port, self._key = _free_port(), secrets.token_urlsafe(24)
            self._base = f"http://127.0.0.1:{port}"
            slots = paths.cache_dir() / "llm-slots"
            slots.mkdir(parents=True, exist_ok=True)
            threads = self.cfg.llm.local_threads or default_threads()
            args = [
                str(self._server), "-m", str(self._gguf), "--host", "127.0.0.1", "--port", str(port),
                "-c", str(CONTEXT), "-t", str(threads), "-np", "1", "--no-webui",
                "--slot-save-path", f"{slots}/", "--log-disable",
            ]
            env = {**os.environ, "LD_LIBRARY_PATH": str(self._server.parent), "LLAMA_API_KEY": self._key}
            self._proc = await asyncio.create_subprocess_exec(
                *args, env=env, stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL, start_new_session=True,
            )
            try:
                await self._wait_healthy()
                await self._load_prompt_state(slots)
            except BaseException:
                await self._stop()
                raise
            log.info("local LLM ready in %d ms (%s, %d threads)", (time.monotonic() - t0) * 1000, self.model, threads)

    async def _wait_healthy(self) -> None:
        deadline = time.monotonic() + START_TIMEOUT_S
        while time.monotonic() < deadline:
            if self._proc is None or self._proc.returncode is not None:
                raise CleanerError("error", f"llama-server exited (code {self._proc.returncode if self._proc else '?'})")
            with contextlib.suppress(httpx.HTTPError):
                if (await self._client.get(f"{self._base}/health")).status_code == 200:
                    return
            await asyncio.sleep(0.2)
        raise CleanerError("timeout", "llama-server didn't start in time")

    async def _load_prompt_state(self, slots: Path) -> None:
        resp = await self._post("/apply-template", {
            "messages": [{"role": "system", "content": self._prompt}, {"role": "user", "content": USER_MARK}],
            "chat_template_kwargs": {"enable_thinking": False},
        })
        text = resp.json()["prompt"]
        i = text.index(USER_MARK)
        self._prefix, self._suffix = text[:i], text[i + len(USER_MARK):]
        name = hashlib.sha256(f"{self._gguf.name}\n{self._prefix}".encode()).hexdigest()[:16] + ".bin"
        if (slots / name).exists():
            with contextlib.suppress(CleanerError, httpx.HTTPError, KeyError, ValueError):
                r = await self._post("/slots/0?action=restore", {"filename": name})
                if r.json().get("n_restored", 0) > 0:
                    return
        # First run for this model and prompt: read the prompt once (slow), then keep the result.
        await self._post("/completion", {"prompt": self._prefix, "n_predict": 0, "cache_prompt": True})
        await self._post("/slots/0?action=save", {"filename": name})

    async def _idle_loop(self) -> None:
        while True:
            await asyncio.sleep(IDLE_CHECK_S)
            running = self._proc is not None and self._proc.returncode is None
            if running and time.monotonic() - self._last_used > self.cfg.llm.local_idle_unload_s:
                log.info("local LLM idle for %.0f s; unloading", self.cfg.llm.local_idle_unload_s)
                async with self._lock:
                    await self._stop()
                self._ready = None

    async def _stop(self) -> None:
        proc, self._proc = self._proc, None
        if proc is None or proc.returncode is not None:
            return
        proc.terminate()
        try:
            await asyncio.wait_for(proc.wait(), 5)
        except TimeoutError:
            proc.kill()
            await proc.wait()

    async def aclose(self) -> None:
        if self._idle_task is not None:
            self._idle_task.cancel()
        if self._ready is not None and not self._ready.done():
            self._ready.cancel()
            with contextlib.suppress(BaseException):
                await self._ready
        await self._stop()
        await self._client.aclose()

    # Clean-up

    async def _post(self, path: str, body: dict[str, Any], timeout: float | None = None) -> httpx.Response:
        try:
            resp = await self._client.post(
                f"{self._base}{path}", json=body, headers={"Authorization": f"Bearer {self._key}"},
                timeout=timeout if timeout is not None else httpx.USE_CLIENT_DEFAULT,
            )
        except httpx.TimeoutException as e:
            raise CleanerError("timeout", f"local LLM: no response within {timeout}s") from e
        except httpx.HTTPError as e:
            raise CleanerError("error", f"local LLM: {e}") from e
        if resp.status_code != 200:
            raise CleanerError("error", f"local LLM: HTTP {resp.status_code}", resp.status_code)
        return resp

    async def clean(self, text: str, *, mode: str, instructions: str, bias_terms: list[str]) -> CleanResult:
        t0 = time.monotonic()
        budget = self.cfg.llm.local_timeout_s
        await self.warm()
        assert self._ready is not None
        try:
            await asyncio.wait_for(asyncio.shield(self._ready), timeout=budget)
        except TimeoutError as e:
            raise CleanerError("timeout", "local LLM still loading") from e  # it keeps loading for next time
        except CleanerError:
            raise
        except Exception as e:
            raise CleanerError("error", f"local LLM failed to start: {e}") from e

        mode_desc = self.cfg.modes.get(mode, self.cfg.modes["default"])
        payload = build_payload(text, mode=mode, mode_desc=mode_desc, instructions=instructions, bias_terms=bias_terms)
        remaining = max(0.5, budget - (time.monotonic() - t0))
        resp = await self._post("/completion", {
            "prompt": self._prefix + payload + self._suffix,
            "n_predict": min(1024, 2 * estimate_tokens(payload) + 64),
            "temperature": 0.0,
            "cache_prompt": True,
        }, timeout=remaining)
        self._last_used = time.monotonic()
        try:
            data = resp.json()
            out = data["content"]
        except (ValueError, KeyError) as e:
            raise CleanerError("error", "local LLM returned an unexpected response") from e
        if data.get("stop_type") not in ("eos", "word"):  # "limit": cut off, would drop the end of the dictation
            raise CleanerError("truncated", f"local LLM stopped: {data.get('stop_type')}")
        # No token counts: they feed the cloud cost estimate in `gillspeak stats`, and this costs nothing.
        return CleanResult(strip_echo(out, text), llm_ms=int((time.monotonic() - t0) * 1000))


def _log_start_failure(task: asyncio.Task[None]) -> None:
    if not task.cancelled() and task.exception() is not None:
        log.warning("local LLM didn't start: %s", task.exception())
