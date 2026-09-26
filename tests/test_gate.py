import pytest

from murmur.config import GateConfig
from murmur.gate import decide

LONG = "this sentence has quite a few words in it so it should clearly be sent along"


@pytest.mark.parametrize(
    "text, kw, expected, reason_prefix",
    [
        ("Short note.", {}, False, "short"),
        (LONG, {}, True, "words"),
        (LONG, {"raw": True}, False, "raw"),
        (LONG, {"provider": "none"}, False, "provider_none"),
        ("Short note.", {"mode": "formal"}, True, "mode:formal"),
        ("Short note.", {"always": True}, True, "always"),
        ("Thursday, sorry Friday.", {}, True, "correction"),
        ("Send it to Tom, I mean Sam.", {}, True, "correction"),
        ("Scratch that.", {}, True, "correction"),
        ("Make that three.", {}, True, "correction"),
        ("First milk, second eggs.", {}, True, "list"),
        ("Firstly this, secondly that.", {}, True, "list"),
        ("Number one, be kind.", {}, True, "list"),
        ("Todo:\n- milk", {}, True, "list"),
        ("", {}, False, "empty"),
        ("I'm sorryish", {}, False, "short"),
    ],
)
def test_decide(text, kw, expected, reason_prefix):
    use, reason = decide(text, **kw)
    assert use is expected
    assert reason.startswith(reason_prefix)


def test_min_words_configurable():
    assert decide("one two three", cfg=GateConfig(min_words=3))[0] is True
    assert decide("one two", cfg=GateConfig(min_words=3))[0] is False


def test_backoff():
    assert decide(LONG, backoff_until=100.0, now=50.0) == (False, "rate_limit_backoff")
    assert decide(LONG, backoff_until=100.0, now=150.0)[0] is True
