from datetime import UTC, datetime, timedelta

from murmur.history import History, Record, compute_stats, percentile, word_diff


def test_save_and_recent(tmp_path):
    h = History(tmp_path / "h.db", keep_days=30)
    h.save(Record(raw_text="um hello", rules_text="Hello", final_text="Hello.", gate_llm=0, llm_status="skipped", total_ms=300, words=1))
    rows = h.recent(5)
    assert rows[0]["raw_text"] == "um hello"
    assert h.last_final_text() == "Hello."


def test_keep_days_zero_stores_metrics_only(tmp_path):
    h = History(tmp_path / "h.db", keep_days=0)
    h.save(Record(raw_text="secret", rules_text="secret", final_text="secret", total_ms=400, words=1))
    row = h.recent(1)[0]
    assert row["raw_text"] is None and row["final_text"] is None
    assert row["total_ms"] == 400


def test_purge_drops_old_text(tmp_path):
    h = History(tmp_path / "h.db", keep_days=30)
    old = (datetime.now(UTC) - timedelta(days=40)).isoformat(timespec="seconds")
    h.save(Record(created_at=old, raw_text="old", final_text="old", total_ms=1))
    h.save(Record(raw_text="new", final_text="new", total_ms=1))
    assert h.purge() == 1
    rows = h.recent(5)
    assert [r["raw_text"] for r in rows] == ["new", None]
    assert len(rows) == 2  # metrics kept


def test_word_diff():
    assert word_diff("the meeting is thursday sorry friday", "The meeting is Friday.") == (
        "[-the-] {+The+} meeting is [-thursday sorry friday-] {+Friday.+}"
    )


def test_percentile():
    assert percentile([], 50) is None
    assert percentile([100, 200, 300, 400, 500], 50) == 300
    assert percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 90) == 9.1


def test_stats():
    now = datetime.now(UTC)
    rows = [
        {"created_at": (now - timedelta(days=10)).isoformat(), "gate_llm": 1, "llm_status": "ok", "total_ms": 1000,
         "input_tokens": 1_000_000, "output_tokens": 100_000, "words": 20},
        {"created_at": now.isoformat(), "gate_llm": 0, "llm_status": "skipped", "total_ms": 500,
         "input_tokens": None, "output_tokens": None, "words": 3},
        {"created_at": now.isoformat(), "gate_llm": 1, "llm_status": "rejected:preamble", "total_ms": 1200,
         "input_tokens": 0, "output_tokens": 0, "words": 5},
    ]
    st = compute_stats(rows, 30, input_per_m=0.10, output_per_m=0.40)
    assert st.dictations == 3 and st.words == 28
    assert st.llm_calls == 2 and st.llm_status == {"ok": 1, "rejected": 1}
    assert st.p50_llm_ms == 1000 and st.p50_nollm_ms == 500
    assert abs(st.cost_window - 0.14) < 1e-9
    assert abs(st.cost_month_est - 0.14 * 3) < 0.01  # 10 days of data -> x3


def test_old_rejection_reasons_are_scrubbed(tmp_path):
    """Regression (Codex review #2): databases written by 0.5.0 hold dictated numbers in llm_status."""
    h = History(tmp_path / "h.db", keep_days=0)
    h.save(Record(llm_status="rejected:missing_number:0412345678", total_ms=1))
    h.save(Record(llm_status="rejected:missing_term:Supabase", total_ms=1))
    h.save(Record(llm_status="rejected:too_short:0.20", total_ms=1))
    h.purge()
    assert [r["llm_status"] for r in h.recent(3)] == ["rejected:too_short:0.20", "rejected:missing_term", "rejected:missing_number"]
