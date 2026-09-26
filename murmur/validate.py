"""Sanity checks on LLM output. A rejection means the rules output is pasted instead."""

from __future__ import annotations

import re

from .gate import CORRECTION_RE

PREAMBLE_RE = re.compile(r"^(?:sure|here(?:'|’| i)s|okay|certainly|the (?:cleaned|corrected))\b", re.IGNORECASE)
NUMBER_RE = re.compile(r"\d[\d,.:/-]*")


def _numbers(text: str) -> set[str]:
    return {m.group(0).rstrip(",.:/-").replace(",", "") for m in NUMBER_RE.finditer(text)}


def check(
    inp: str,
    out: str,
    mode: str = "default",
    *,
    bias_terms: list[str] | None = None,
    check_bias: bool = False,
) -> tuple[bool, str]:
    """Return (ok, reason). reason is "" when ok."""
    out_s = out.strip()
    if not out_s:
        return False, "empty"
    in_len = max(len(inp.strip()), 1)
    ratio = len(out_s) / in_len
    max_ratio = 2.5 if mode == "formal" else 1.6
    if ratio < 0.4:
        return False, f"too_short:{ratio:.2f}"
    if ratio > max_ratio:
        return False, f"too_long:{ratio:.2f}"
    if PREAMBLE_RE.search(out_s) and not PREAMBLE_RE.search(inp.strip()):
        return False, "preamble"
    if not CORRECTION_RE.search(inp):
        out_numbers = _numbers(out_s)
        # Also accept numbers the model joined or reformatted: "4 8 2" -> "482", "6 45" -> "6:45".
        out_digits = re.sub(r"[\s,:]", "", out_s)
        missing = sorted(n for n in _numbers(inp) if n not in out_numbers and n not in out_digits)
        if missing:
            return False, f"missing_number:{missing[0]}"
    if check_bias and bias_terms:
        low_in, low_out = inp.lower(), out_s.lower()
        for term in bias_terms:
            if term.lower() in low_in and term.lower() not in low_out:
                return False, f"missing_term:{term}"
    return True, ""
