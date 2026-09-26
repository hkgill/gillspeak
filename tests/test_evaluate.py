import httpx
import respx

from murmur import evaluate
from murmur.cleaner import GEMINI_BASE
from murmur.evaluate import DEFAULT_SET, load_cases, score


def test_eval_set_is_valid():
    cases = load_cases(DEFAULT_SET)
    assert len(cases) >= 50
    for c in cases:
        assert c["input"] and isinstance(c["must_contain"], list) and isinstance(c["must_not_contain"], list)


def test_score():
    case = {"must_contain": ["Friday"], "must_not_contain": ["Thursday"]}
    assert score(case, "The review moved to Friday.") == []
    assert score(case, "The review moved to friday, not thursday.") == ["missing 'Friday'", "contains 'Thursday'"]


@respx.mock
def test_run_eval_against_mock(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("GEMINI_API_KEY", "k")
    monkeypatch.setattr("murmur.secrets._keyring_get", lambda user: None)
    f = tmp_path / "set.jsonl"
    f.write_text(
        '{"input": "the meeting is thursday sorry friday", "must_contain": ["Friday"], "must_not_contain": ["Thursday"]}\n'
        '{"input": "pay 420 dollars", "must_contain": ["$420"], "must_not_contain": []}\n'
    )
    respx.get(url__regex=rf"{GEMINI_BASE}/models/[^:]+$").mock(return_value=httpx.Response(200, json={}))
    answers = iter(["The meeting is Friday.", "Pay 42 dollars."])
    respx.post(url__regex=r".*:generateContent").mock(
        side_effect=lambda req: httpx.Response(
            200, json={"candidates": [{"content": {"parts": [{"text": next(answers)}]}, "finishReason": "STOP"}]}
        )
    )
    assert evaluate.run_eval(str(f)) == 1  # 1/2 < 95%
    out = capsys.readouterr().out
    assert "Pass rate 1/2" in out
    assert "validator rejected: missing_number:420" in out
