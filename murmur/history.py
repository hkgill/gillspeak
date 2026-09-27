"""Local SQLite history of dictations: raw vs cleaned text plus timings, for review and tuning."""

from __future__ import annotations

import difflib
import sqlite3
import threading
from dataclasses import asdict, dataclass, field, fields
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS dictations (
  id            INTEGER PRIMARY KEY,
  created_at    TEXT NOT NULL,
  audio_seconds REAL,
  asr_engine    TEXT,
  asr_ms        INTEGER,
  raw_text      TEXT,
  rules_text    TEXT,
  gate_llm      INTEGER,
  gate_reason   TEXT,
  llm_model     TEXT,
  prompt_ver    TEXT,
  llm_ms        INTEGER,
  llm_status    TEXT,
  final_text    TEXT,
  mode          TEXT,
  input_tokens  INTEGER,
  output_tokens INTEGER,
  words         INTEGER,
  total_ms      INTEGER,
  inject_status TEXT
);
CREATE INDEX IF NOT EXISTS dictations_created ON dictations(created_at);
"""

TEXT_COLUMNS = ("raw_text", "rules_text", "final_text")


def now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


@dataclass
class Record:
    created_at: str = field(default_factory=now_iso)
    audio_seconds: float | None = None
    asr_engine: str | None = None
    asr_ms: int | None = None
    raw_text: str | None = None
    rules_text: str | None = None
    gate_llm: int | None = None
    gate_reason: str | None = None
    llm_model: str | None = None
    prompt_ver: str | None = None
    llm_ms: int | None = None
    llm_status: str | None = None
    final_text: str | None = None
    mode: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    words: int | None = None
    total_ms: int | None = None
    inject_status: str | None = None


class History:
    def __init__(self, path: Path, keep_days: int = 30):
        self.path = path
        self.keep_days = keep_days
        path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(SCHEMA)
        self._db.commit()

    def close(self) -> None:
        self._db.close()

    def save(self, rec: Record) -> int:
        data = asdict(rec)
        if self.keep_days <= 0:
            for col in TEXT_COLUMNS:
                data[col] = None
        cols = ", ".join(data)
        marks = ", ".join("?" for _ in data)
        with self._lock:
            cur = self._db.execute(f"INSERT INTO dictations ({cols}) VALUES ({marks})", tuple(data.values()))
            self._db.commit()
            return int(cur.lastrowid or 0)

    def purge(self) -> int:
        """Retention: drop text older than keep_days (keep metrics for stats); keep_days=0 keeps no text at all."""
        cutoff = (datetime.now(UTC) - timedelta(days=max(self.keep_days, 0))).isoformat(timespec="seconds")
        sets = ", ".join(f"{c} = NULL" for c in TEXT_COLUMNS)
        with self._lock:
            cur = self._db.execute(
                f"UPDATE dictations SET {sets} WHERE created_at < ? AND (raw_text IS NOT NULL OR rules_text IS NOT NULL OR final_text IS NOT NULL)",
                (cutoff,),
            )
            self._db.commit()
            return cur.rowcount

    def recent(self, n: int = 20) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._db.execute("SELECT * FROM dictations ORDER BY id DESC LIMIT ?", (n,)).fetchall()
        return [dict(r) for r in rows]

    def last_final_text(self) -> str | None:
        with self._lock:
            row = self._db.execute(
                "SELECT final_text FROM dictations WHERE final_text IS NOT NULL ORDER BY id DESC LIMIT 1"
            ).fetchone()
        return row[0] if row else None

    def rows_since(self, days: int) -> list[dict[str, Any]]:
        cutoff = (datetime.now(UTC) - timedelta(days=days)).isoformat(timespec="seconds")
        with self._lock:
            rows = self._db.execute("SELECT * FROM dictations WHERE created_at >= ?", (cutoff,)).fetchall()
        return [dict(r) for r in rows]


def word_diff(a: str, b: str) -> str:
    """Inline word diff: [-removed-] {+added+}."""
    aw, bw = a.split(), b.split()
    out: list[str] = []
    for op, i1, i2, j1, j2 in difflib.SequenceMatcher(a=aw, b=bw, autojunk=False).get_opcodes():
        if op == "equal":
            out.extend(aw[i1:i2])
            continue
        if op in ("delete", "replace"):
            out.append("[-" + " ".join(aw[i1:i2]) + "-]")
        if op in ("insert", "replace"):
            out.append("{+" + " ".join(bw[j1:j2]) + "+}")
    return " ".join(out)


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


@dataclass
class Stats:
    days: int
    dictations: int
    words: int
    llm_calls: int
    llm_pct: float
    llm_status: dict[str, int]
    p50_llm_ms: float | None
    p90_llm_ms: float | None
    p50_nollm_ms: float | None
    p90_nollm_ms: float | None
    input_tokens: int
    output_tokens: int
    cost_window: float
    cost_month_est: float


def compute_stats(rows: list[dict[str, Any]], days: int, input_per_m: float, output_per_m: float) -> Stats:
    llm_rows = [r for r in rows if r.get("gate_llm")]
    with_llm = [float(r["total_ms"]) for r in rows if r.get("total_ms") is not None and r.get("llm_status") == "ok"]
    without = [float(r["total_ms"]) for r in rows if r.get("total_ms") is not None and not r.get("gate_llm")]
    status: dict[str, int] = {}
    for r in llm_rows:
        key = str(r.get("llm_status") or "?").split(":")[0]
        status[key] = status.get(key, 0) + 1
    tin = sum(int(r.get("input_tokens") or 0) for r in rows)
    tout = sum(int(r.get("output_tokens") or 0) for r in rows)
    cost = tin / 1e6 * input_per_m + tout / 1e6 * output_per_m
    span_days = days
    if rows:
        first = min(datetime.fromisoformat(r["created_at"]) for r in rows)
        span_days = max(1.0, min(days, (datetime.now(UTC) - first).total_seconds() / 86400))
    return Stats(
        days=days,
        dictations=len(rows),
        words=sum(int(r.get("words") or 0) for r in rows),
        llm_calls=len(llm_rows),
        llm_pct=100.0 * len(llm_rows) / len(rows) if rows else 0.0,
        llm_status=status,
        p50_llm_ms=percentile(with_llm, 50),
        p90_llm_ms=percentile(with_llm, 90),
        p50_nollm_ms=percentile(without, 50),
        p90_nollm_ms=percentile(without, 90),
        input_tokens=tin,
        output_tokens=tout,
        cost_window=cost,
        cost_month_est=cost * 30.0 / span_days,
    )


RECORD_FIELDS = [f.name for f in fields(Record)]
