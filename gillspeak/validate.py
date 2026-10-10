"""Sanity checks on LLM output. A rejection means the rules output is pasted instead."""

from __future__ import annotations

import re

from .gate import CORRECTION_RE

PREAMBLE_RE = re.compile(r"^(?:sure|here(?:'|’| i)s|okay|certainly|the (?:cleaned|corrected))\b", re.IGNORECASE)
NUMBER_RE = re.compile(r"\d[\d,.:/-]*")


# A run of numbers separated by single spaces, spoken digit by digit or as a time: "4 8 2", "6 45".
GROUP_RE = re.compile(r"\d[\d,.:/-]*(?: \d[\d,.:/-]*)*")

# A decimal point between digits: "1.50" must never come back as "150", or the other way round.
DECIMAL_RE = re.compile(r"\d\.\d")


def _numbers(text: str) -> set[str]:
    return {m.group(0).rstrip(",.:/-").replace(",", "") for m in NUMBER_RE.finditer(text)}


def _digits(s: str) -> str:
    return re.sub(r"\D", "", s)


def _undecimal_digits(group: str) -> str:
    """The digits of a group's numbers that have no decimal point."""
    return "".join(_digits(t) for t in group.split(" ") if not DECIMAL_RE.search(t))


def _missing_numbers(inp: str, out: str) -> bool:
    """True if a number from the input is gone or changed. Each number must appear as a whole token,
    or a spaced group may come back joined/reformatted as a whole ("4 8 2" -> "482", "6 45" -> "6:45").
    Never a substring match: "250" must not be found inside "1250"."""
    out_tokens = _numbers(out)
    out_joined = {_undecimal_digits(m.group(0)) for m in GROUP_RE.finditer(out)} | {
        _digits(t) for t in out_tokens if not DECIMAL_RE.search(t)
    }
    for m in GROUP_RE.finditer(inp):
        group = m.group(0)
        numbers = _numbers(group)
        if all(n in out_tokens for n in numbers):
            continue
        # Decimals must come back exactly; the rest of the group may come back joined ("1.50 4 8 2" -> "1.50 482").
        if all(n in out_tokens for n in numbers if DECIMAL_RE.search(n)) and _undecimal_digits(group) in out_joined:
            continue
        return True
    return False


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
    if not CORRECTION_RE.search(inp) and _missing_numbers(inp, out_s):
        return False, "missing_number"  # reasons are logged and stored: never include dictated values
    if check_bias and bias_terms:
        low_in, low_out = inp.lower(), out_s.lower()
        for term in bias_terms:
            if term.lower() in low_in and term.lower() not in low_out:
                return False, "missing_term"
    return True, ""
