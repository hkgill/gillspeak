import httpx
import respx

from gillspeak import evaluate
from gillspeak.cleaner import GEMINI_BASE
from gillspeak.evaluate import DEFAULT_SET, load_cases, score


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
    monkeypatch.setattr("gillspeak.secrets._keyring_get", lambda user: None)
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
    assert "validator rejected: missing_number" in out


def _mock_gemini(responses):
    respx.get(url__regex=rf"{GEMINI_BASE}/models/[^:]+$").mock(return_value=httpx.Response(200, json={}))
    it = iter(responses)

    def reply(req):
        r = next(it)
        if r == 429:
            return httpx.Response(429, json={})
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": r}]}, "finishReason": "STOP"}]})

    return respx.post(url__regex=r".*:generateContent").mock(side_effect=reply)


def _eval_env(tmp_path, monkeypatch, n):
    monkeypatch.setenv("GEMINI_API_KEY", "k")
    monkeypatch.setattr("gillspeak.secrets._keyring_get", lambda user: None)
    monkeypatch.setattr(evaluate, "RATE_LIMIT_WAIT_S", 0.0)
    f = tmp_path / "set.jsonl"
    f.write_text("".join('{"input": "the meeting is on friday", "must_contain": ["Friday"]}\n' for _ in range(n)))
    return str(f)


@respx.mock
def test_run_eval_retries_once_after_rate_limit(tmp_path, monkeypatch, capsys):
    f = _eval_env(tmp_path, monkeypatch, 2)
    route = _mock_gemini([429, "The meeting is on Friday.", "The meeting is on Friday."])
    assert evaluate.run_eval(f) == 0
    out = capsys.readouterr().out
    assert "rate limited; waiting" in out and "Pass rate 2/2" in out
    assert route.call_count == 3


@respx.mock
def test_run_eval_stops_when_still_rate_limited(tmp_path, monkeypatch, capsys):
    f = _eval_env(tmp_path, monkeypatch, 3)
    route = _mock_gemini(["The meeting is on Friday.", 429, 429])
    assert evaluate.run_eval(f) == 1
    out = capsys.readouterr().out
    assert "stopping" in out and "Pass rate 1/1" in out and "stopped early after 1/3" in out
    assert route.call_count == 3  # nothing sent after the second 429


def test_score_accepts_alternatives():
    """Regression: a correctly capitalised list item ("Milk") failed a case-sensitive "milk"."""
    case = {"must_contain": [["milk", "Milk"], "\n"], "must_not_contain": []}
    assert score(case, "Shopping list\n- Milk") == []
    assert score(case, "Shopping list\n- milk") == []
    assert score(case, "Shopping list - bread") == ["missing 'milk' or 'Milk'", "missing '\\n'"]


def test_every_case_is_passable():
    """A forbidden phrase (matched case-insensitively) must not sit inside a required one,
    or no output could satisfy the case."""
    for c in load_cases(DEFAULT_SET):
        wanted = [o for s in c["must_contain"] for o in (s if isinstance(s, list) else [s])]
        for bad in c["must_not_contain"]:
            clash = [w for w in wanted if bad.lower() in w.lower()]
            assert not clash, f"line {c['_line']}: must_not_contain {bad!r} blocks must_contain {clash}"


def test_shipped_prompt_exists_and_is_current():
    from gillspeak.cleaner import PROMPT_VERSION, system_prompt

    assert PROMPT_VERSION == "clean_v2"
    text = system_prompt()
    assert "let me rephrase" in text and "Output ONLY the final text" in text
    assert system_prompt("clean_v1")  # older versions stay loadable for history rows
