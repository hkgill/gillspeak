"""The user's own post-process command, run through a real shell."""

import time

import pytest

from gillspeak.postprocess import PostProcessError, run_command


async def test_text_goes_in_on_stdin_and_stdout_comes_back():
    assert await run_command("tr a-z A-Z", "hello", 5) == "HELLO"


async def test_unicode_and_surrounding_whitespace():
    assert await run_command("cat; echo", "café\n", 5) == "café"


@pytest.mark.parametrize(
    ("command", "reason"),
    [("exit 3", "status 3"), ("cat >/dev/null", "nothing"), ("definitely-not-a-command-xyz", "status 127")],
)
async def test_failures_raise_without_quoting_the_text(command, reason):
    with pytest.raises(PostProcessError) as e:
        await run_command(command, "private dictation", 5)
    assert reason in str(e.value) and "private" not in str(e.value)


async def test_timeout_stops_the_command_and_its_children():
    t0 = time.monotonic()
    with pytest.raises(PostProcessError, match="timed out"):
        await run_command("sleep 30 & wait", "hello", 0.1)
    assert time.monotonic() - t0 < 5
