"""`gillspeak eval`: run the LLM evaluation set against the configured model + prompt."""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .history import percentile

DEFAULT_SET = Path(__file__).resolve().parent / "evals" / "llm_eval.jsonl"
RATE_LIMIT_WAIT_S = 30.0  # one wait-and-retry per case on HTTP 429, then stop the run


@dataclass
class CaseResult:
    case: dict[str, Any]
    output: str = ""
    passed: bool = False
    reasons: list[str] = field(default_factory=list)
    ms: float = 0.0


def load_cases(path: Path) -> list[dict[str, Any]]:
    cases = []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        case = json.loads(line)
        case.setdefault("must_contain", [])
        case.setdefault("must_not_contain", [])
        case.setdefault("mode", "default")
        case["_line"] = n
        cases.append(case)
    return cases


def score(case: dict[str, Any], output: str) -> list[str]:
    """must_contain is case-sensitive (names, figures); an entry may be a list of acceptable
    alternatives (e.g. ["milk", "Milk"] for a list item). must_not_contain is case-insensitive."""
    reasons = []
    for s in case["must_contain"]:
        options = s if isinstance(s, list) else [s]
        if not any(o in output for o in options):
            reasons.append(f"missing {' or '.join(map(repr, options))}")
    low = output.lower()
    reasons += [f"contains {s!r}" for s in case["must_not_contain"] if s.lower() in low]
    return reasons


async def _run_cases(
    cfg: Any, cases: list[dict[str, Any]], verbose: bool, delay: float = 0.0, rate_limit_wait: float | None = None
) -> tuple[list[CaseResult], bool]:
    """Returns (results, stopped_early). Requests are spaced `delay` seconds apart to stay under free-tier quotas."""
    from . import validate
    from .cleaner import CleanerError, make_cleaner
    from .config import load_dictionary
    from .rules import RulesEngine

    dictionary = load_dictionary()
    rules = RulesEngine(cfg.rules, dictionary)
    cleaner = make_cleaner(cfg)
    await cleaner.warm()
    wait_ready = getattr(cleaner, "wait_ready", None)
    if wait_ready is not None:
        await wait_ready()  # a local model's first start can take longer than one dictation's budget
    wait = RATE_LIMIT_WAIT_S if rate_limit_wait is None else rate_limit_wait
    results: list[CaseResult] = []
    stopped = False
    try:
        for i, case in enumerate(cases):
            if i and delay:
                await asyncio.sleep(delay)
            res = CaseResult(case)
            text = rules.apply(case["input"])
            t0 = time.monotonic()
            try:
                try:
                    out = await cleaner.clean(text, mode=case["mode"], instructions=cfg.llm.instructions, bias_terms=dictionary.bias)
                except CleanerError as e:
                    if e.kind != "rate_limited":
                        raise
                    print(f"   rate limited; waiting {wait:.0f} s before retrying line {case['_line']}")
                    await asyncio.sleep(wait)
                    t0 = time.monotonic()
                    out = await cleaner.clean(text, mode=case["mode"], instructions=cfg.llm.instructions, bias_terms=dictionary.bias)
                res.output = rules.apply_dictionary(out.text)
                ok, why = validate.check(text, out.text, case["mode"], bias_terms=dictionary.bias, check_bias=cfg.llm.check_bias_terms)
                if not ok:
                    res.reasons.append(f"validator rejected: {why}")
            except CleanerError as e:
                if e.kind == "rate_limited":
                    print(f"   still rate limited at line {case['_line']}; stopping (raise --delay or use --limit)")
                    stopped = True
                    break
                res.reasons.append(f"{e.kind}: {e}")
            res.ms = (time.monotonic() - t0) * 1000
            res.reasons += score(case, res.output) if res.output else []
            res.passed = not res.reasons
            results.append(res)
            mark = "✅" if res.passed else "❌"
            if verbose or not res.passed:
                print(f"{mark} line {case['_line']} ({res.ms:.0f} ms): {res.output!r}")
                for r in res.reasons:
                    print(f"     {r}")
    finally:
        await cleaner.aclose()
    return results, stopped


def run_eval(
    file: str | None = None, *, limit: int | None = None, delay: float = 0.0, verbose: bool = False, provider: str | None = None
) -> int:
    """Scores the LLM clean-up. [provider] overrides llm.provider for this run only (it's "none" by default)."""
    from .config import load

    cfg = load()
    if provider:
        cfg.llm.provider = provider
    if cfg.llm.provider == "none":
        print("LLM clean-up is off (llm.provider = none), so there is nothing to evaluate.")
        print("To score it without turning it on: gillspeak eval --provider local (or gemini)")
        return 1
    path = Path(file) if file else DEFAULT_SET
    if not path.exists():
        print(f"{path} not found")
        return 1
    cases = load_cases(path)[:limit]
    model = cfg.llm.local_model if cfg.llm.provider == "local" else cfg.llm.model
    print(f"{len(cases)} cases from {path}, model {model}\n")
    results, stopped = asyncio.run(_run_cases(cfg, cases, verbose, delay))
    passed = sum(r.passed for r in results)
    lat = [r.ms for r in results]
    rate = passed / len(results) if results else 0.0
    note = f", stopped early after {len(results)}/{len(cases)} cases" if stopped else ""
    print(f"\nPass rate {passed}/{len(results)} = {rate:.1%} (acceptance ≥ 95%{note})")
    if lat:
        print(f"Latency p50 {percentile(lat, 50):.0f} ms, p90 {percentile(lat, 90):.0f} ms")
    return 0 if rate >= 0.95 and not stopped else 1
