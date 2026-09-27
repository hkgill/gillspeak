import pytest

from murmur.validate import check

IN = "The invoice for the Henderson job is $4,250 and due on 12/10 at 3:30."


@pytest.mark.parametrize(
    "inp, out, mode, ok, reason",
    [
        (IN, "The invoice for the Henderson job is $4,250 and is due on 12/10 at 3:30.", "default", True, ""),
        (IN, "   ", "default", False, "empty"),
        (IN, "Invoice.", "default", False, "too_short"),
        (IN, IN * 2, "default", False, "too_long"),
        (IN, IN + " " + IN[:40], "formal", True, ""),
        (IN, "Sure! The invoice for the Henderson job is $4,250, due 12/10 at 3:30.", "default", False, "preamble"),
        (IN, "Here's the invoice for the Henderson job: $4,250, due 12/10 at 3:30.", "default", False, "preamble"),
        (IN, "The invoice for the Henderson job is $4,205 and due on 12/10 at 3:30.", "default", False, "missing_number"),
        (IN, "The invoice for the Henderson job is $4250 and due on 12/10 at 3:30.", "default", True, ""),
        ("Meet at 3, sorry 4 pm.", "Meet at 4 pm.", "default", True, ""),
        ("Sure, I can do that.", "Sure, I can do that.", "default", True, ""),
        ("The order number is 4 8 2 7 1 9.", "The order number is 482719.", "default", True, ""),
        ("Lands at 6 45 on the 3rd.", "Lands at 6:45 on the 3rd.", "default", True, ""),
        ("Pay 120 dollars and 7 cents.", "Pay 120 dollars and cents.", "default", False, "missing_number"),
        # Regression (Codex review #8): a number was "present" if it appeared anywhere in the joined digits.
        ("Pay the 250 dollar invoice.", "Pay the 1250 dollar invoice.", "default", False, "missing_number"),
        ("Pay the 250 dollar invoice.", "Pay the 2500 dollar invoice.", "default", False, "missing_number"),
        ("Room 12 at 4.", "Room 124.", "default", False, "missing_number"),
        ("Call 0412 345 678 today.", "Call 0412 345 678 today.", "default", True, ""),
        ("Call 0412 345 678 today.", "Call 0412345678 today.", "default", True, ""),
        ("Total $1,299.95 today.", "Total $1,299.95 today.", "default", True, ""),
        ("Standup at 9 30.", "Standup at 9:30.", "default", True, ""),
    ],
)
def test_check(inp, out, mode, ok, reason):
    got_ok, got_reason = check(inp, out, mode)
    assert got_ok is ok
    assert got_reason.startswith(reason)


def test_bias_terms():
    inp = "Deploy the Supabase function today please."
    out = "Deploy the Superbase function today, please."
    assert check(inp, out, bias_terms=["Supabase"], check_bias=True) == (False, "missing_term")
    assert check(inp, out, bias_terms=["Supabase"], check_bias=False)[0] is True


def test_reasons_never_contain_dictated_values():
    """Regression (Codex review #2): reasons like "missing_number:0412345678" were logged and kept in
    history metadata forever, even with keep_days = 0."""
    ok, why = check("Call me on 0412 345 678 please.", "Call me please, thanks a lot.", "default")
    assert not ok and why == "missing_number" and "0412" not in why
