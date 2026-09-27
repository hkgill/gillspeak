"""Decide whether a transcript is worth an LLM round trip (~400-900 ms)."""

from __future__ import annotations

import re
import time

from .config import GateConfig

CORRECTION_RE = re.compile(
    r"\b(?:sorry|I mean|actually|scratch that|no wait|wait no|let me rephrase|correction|or rather|make that)\b",
    re.IGNORECASE,
)
LIST_RE = re.compile(r"\bfirst(?:ly)?\b.*\bsecond(?:ly)?\b|\bnumber one\b|\bbullet\b|^- ", re.IGNORECASE | re.DOTALL | re.MULTILINE)


def word_count(text: str) -> int:
    return len(re.findall(r"[\w'’-]+", text))


def decide(
    text: str,
    *,
    mode: str = "default",
    raw: bool = False,
    provider: str = "gemini",
    always: bool = False,
    cfg: GateConfig | None = None,
    backoff_until: float = 0.0,
    now: float | None = None,
) -> tuple[bool, str]:
    """Return (call_llm, reason). The reason is logged to history for tuning."""
    cfg = cfg or GateConfig()
    if raw:
        return False, "raw"
    if provider == "none":
        return False, "provider_none"
    if not text.strip():
        return False, "empty"
    if (now if now is not None else time.monotonic()) < backoff_until:
        return False, "rate_limit_backoff"
    if mode != "default":
        return True, f"mode:{mode}"
    if always:
        return True, "always"
    words = word_count(text)
    if words >= cfg.min_words:
        return True, f"words:{words}"
    if CORRECTION_RE.search(text):
        return True, "correction"
    if LIST_RE.search(text):
        return True, "list"
    return False, f"short:{words}"
