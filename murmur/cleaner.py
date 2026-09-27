"""LLM clean-up: GeminiCleaner (v1), ProxyCleaner (v2 contract), NoopCleaner."""

from __future__ import annotations

import asyncio
import logging
import re
import time
from dataclasses import dataclass
from importlib import resources
from typing import Any, Protocol

import httpx

from .config import Config

log = logging.getLogger(__name__)

PROMPT_VERSION = "clean_v2"
GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
WARM_IDLE_S = 300.0


def system_prompt(version: str = PROMPT_VERSION) -> str:
    return resources.files("murmur.prompts").joinpath(f"{version}.txt").read_text(encoding="utf-8").strip()


class CleanerError(Exception):
    """kind: timeout | offline | rate_limited | auth | blocked | error"""

    def __init__(self, kind: str, message: str = "", status: int | None = None):
        super().__init__(message or kind)
        self.kind = kind
        self.status = status


@dataclass
class CleanResult:
    text: str
    input_tokens: int | None = None
    output_tokens: int | None = None
    llm_ms: int = 0


class Cleaner(Protocol):
    model: str

    async def clean(self, text: str, *, mode: str, instructions: str, bias_terms: list[str]) -> CleanResult: ...

    async def warm(self) -> None: ...

    async def aclose(self) -> None: ...


def build_payload(text: str, *, mode: str, mode_desc: str, instructions: str, bias_terms: list[str]) -> str:
    return (
        f"Mode: {mode} ({mode_desc})\n"
        f"Style instructions: {instructions}\n"
        f"Preferred spellings: {', '.join(bias_terms)}\n"
        "\n"
        f"<transcript>\n{text}\n</transcript>"
    )


def estimate_tokens(text: str) -> int:
    return max(1, (len(text) + 3) // 4)


_TAG_RE = re.compile(r"</?transcript>", re.IGNORECASE)
_FENCE_RE = re.compile(r"^```[\w-]*\n?(.*?)\n?```$", re.DOTALL)


def strip_echo(out: str, original: str) -> str:
    """Remove wrappers the model sometimes adds: <transcript> tags, code fences, quotes."""
    out = _TAG_RE.sub("", out).strip()
    fence = _FENCE_RE.match(out)
    if fence:
        out = fence.group(1).strip()
    for q_open, q_close in (('"', '"'), ("“", "”"), ("'", "'"), ("`", "`")):
        if len(out) >= 2 and out.startswith(q_open) and out.endswith(q_close) and not original.strip().startswith(q_open):
            out = out[len(q_open) : -len(q_close)].strip()
    return out


def parse_gemini_response(data: dict[str, Any], original: str) -> tuple[str, int | None, int | None]:
    candidates = data.get("candidates") or []
    if not candidates:
        reason = (data.get("promptFeedback") or {}).get("blockReason")
        raise CleanerError("blocked" if reason else "error", f"no candidates (blockReason={reason})")
    cand = candidates[0]
    finish = cand.get("finishReason")
    if finish not in ("STOP", "MAX_TOKENS"):
        raise CleanerError("blocked" if finish in ("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII") else "error", f"finishReason={finish}")
    parts = (cand.get("content") or {}).get("parts") or []
    text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
    usage = data.get("usageMetadata") or {}
    return strip_echo(text, original), usage.get("promptTokenCount"), usage.get("candidatesTokenCount")


class _HttpCleaner:
    def __init__(self, cfg: Config, client: httpx.AsyncClient | None = None):
        self.cfg = cfg
        self.model = cfg.llm.model
        self._client = client or httpx.AsyncClient(
            http2=True,
            timeout=httpx.Timeout(cfg.llm.timeout_s, connect=cfg.llm.connect_timeout_s),
        )
        self._last_used: float | None = None  # None = never connected (monotonic time starts near 0 at boot)

    async def _post(self, url: str, *, headers: dict[str, str], json: dict[str, Any]) -> httpx.Response:
        total = self.cfg.llm.timeout_s
        try:
            resp = await asyncio.wait_for(self._client.post(url, headers=headers, json=json), timeout=total)
        except (TimeoutError, httpx.TimeoutException) as e:
            raise CleanerError("timeout", f"no response within {total}s") from e
        except (httpx.ConnectError, httpx.NetworkError) as e:
            raise CleanerError("offline", str(e)) from e
        except httpx.HTTPError as e:
            raise CleanerError("error", str(e)) from e
        finally:
            self._last_used = time.monotonic()
        if resp.status_code == 429:
            raise CleanerError("rate_limited", "HTTP 429", 429)
        if resp.status_code in (400, 401, 403, 404):
            raise CleanerError("auth", f"HTTP {resp.status_code}: {resp.text[:200]}", resp.status_code)
        if resp.status_code != 200:
            raise CleanerError("error", f"HTTP {resp.status_code}", resp.status_code)
        return resp

    def needs_warm(self) -> bool:
        return self._last_used is None or time.monotonic() - self._last_used > WARM_IDLE_S

    async def aclose(self) -> None:
        await self._client.aclose()


class GeminiCleaner(_HttpCleaner):
    def __init__(self, cfg: Config, api_key: str, client: httpx.AsyncClient | None = None):
        super().__init__(cfg, client)
        self._key = api_key
        self._prompt = system_prompt()
        self._extra = cfg.llm.extra_generation_dict()

    @property
    def _headers(self) -> dict[str, str]:
        return {"x-goog-api-key": self._key, "Content-Type": "application/json"}

    def request_body(self, text: str, *, mode: str, instructions: str, bias_terms: list[str]) -> dict[str, Any]:
        mode_desc = self.cfg.modes.get(mode, self.cfg.modes["default"])
        payload = build_payload(text, mode=mode, mode_desc=mode_desc, instructions=instructions, bias_terms=bias_terms)
        gen: dict[str, Any] = {
            "temperature": 0.0,
            "maxOutputTokens": min(1024, 2 * estimate_tokens(payload) + 64),
            "responseMimeType": "text/plain",
        }
        gen.update(self._extra)
        return {
            "systemInstruction": {"parts": [{"text": self._prompt}]},
            "contents": [{"role": "user", "parts": [{"text": payload}]}],
            "generationConfig": gen,
        }

    async def clean(self, text: str, *, mode: str, instructions: str, bias_terms: list[str]) -> CleanResult:
        t0 = time.monotonic()
        body = self.request_body(text, mode=mode, instructions=instructions, bias_terms=bias_terms)
        resp = await self._post(f"{GEMINI_BASE}/models/{self.model}:generateContent", headers=self._headers, json=body)
        try:
            data = resp.json()
        except ValueError as e:
            raise CleanerError("error", "invalid JSON") from e
        out, tin, tout = parse_gemini_response(data, text)
        return CleanResult(out, tin, tout, int((time.monotonic() - t0) * 1000))

    async def warm(self) -> None:
        """Open the TLS connection with a free metadata call so dictation doesn't pay for it."""
        try:
            await self._client.get(f"{GEMINI_BASE}/models/{self.model}", headers=self._headers)
        except httpx.HTTPError as e:
            log.debug("warm-up failed: %s", e)
        self._last_used = time.monotonic()


class ProxyCleaner(_HttpCleaner):
    """v2: same job, done by your own server (POST /v1/clean with a per-device bearer token)."""

    def __init__(self, cfg: Config, token: str, client: httpx.AsyncClient | None = None, device: str = "fedora"):
        super().__init__(cfg, client)
        self._token = token
        self._device = device

    async def clean(self, text: str, *, mode: str, instructions: str, bias_terms: list[str]) -> CleanResult:
        t0 = time.monotonic()
        resp = await self._post(
            self.cfg.llm.proxy_url,
            headers={"Authorization": f"Bearer {self._token}"},
            json={"text": text, "mode": mode, "client": self._device, "prompt_ver": PROMPT_VERSION},
        )
        data = resp.json()
        if data.get("status") != "ok":
            raise CleanerError("error", f"proxy status {data.get('status')}")
        usage = data.get("usage") or {}
        return CleanResult(strip_echo(data.get("text", ""), text), usage.get("in"), usage.get("out"), int((time.monotonic() - t0) * 1000))

    async def warm(self) -> None:
        self._last_used = time.monotonic()


class NoopCleaner:
    model = "none"

    async def clean(self, text: str, *, mode: str, instructions: str, bias_terms: list[str]) -> CleanResult:
        return CleanResult(text)

    def needs_warm(self) -> bool:
        return False

    async def warm(self) -> None:
        pass

    async def aclose(self) -> None:
        pass


def make_cleaner(cfg: Config) -> Cleaner:
    from . import secrets

    if cfg.llm.provider == "gemini":
        key = secrets.get_api_key()
        if not key:
            raise CleanerError("auth", "no Gemini API key (run `murmur set-key`)")
        return GeminiCleaner(cfg, key)
    if cfg.llm.provider == "proxy":
        return ProxyCleaner(cfg, secrets.get_proxy_token() or "")
    return NoopCleaner()
