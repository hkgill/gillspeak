import asyncio
import json

import httpx
import pytest
import respx

from murmur.cleaner import GEMINI_BASE, CleanerError, GeminiCleaner, estimate_tokens, strip_echo
from murmur.config import Config

URL = f"{GEMINI_BASE}/models/gemini-3.5-flash-lite:generateContent"


def ok_response(text, finish="STOP"):
    return {
        "candidates": [{"content": {"parts": [{"text": text}]}, "finishReason": finish}],
        "usageMetadata": {"promptTokenCount": 350, "candidatesTokenCount": 12},
    }


@pytest.fixture
def cfg():
    c = Config()
    c.llm.extra_generation_config = '{"thinkingConfig": {"thinkingBudget": 0}}'
    return c


async def clean(cfg, text="the meeting is thursday sorry friday", mode="default"):
    cleaner = GeminiCleaner(cfg, "secret-key")
    try:
        return await cleaner.clean(text, mode=mode, instructions="Australian English.", bias_terms=["Supabase"])
    finally:
        await cleaner.aclose()


@respx.mock
async def test_success_and_request_shape(cfg):
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=ok_response("The meeting is Friday.")))
    res = await clean(cfg)
    assert res.text == "The meeting is Friday."
    assert (res.input_tokens, res.output_tokens) == (350, 12)
    req = route.calls.last.request
    assert req.headers["x-goog-api-key"] == "secret-key"
    assert "secret-key" not in str(req.url)
    body = json.loads(req.content)
    assert "dictation post-processor" in body["systemInstruction"]["parts"][0]["text"]
    payload = body["contents"][0]["parts"][0]["text"]
    assert "<transcript>\nthe meeting is thursday sorry friday\n</transcript>" in payload
    assert "Preferred spellings: Supabase" in payload
    assert "Style instructions: Australian English." in payload
    assert payload.startswith("Mode: default")
    gen = body["generationConfig"]
    assert gen["temperature"] == 0.0
    assert gen["responseMimeType"] == "text/plain"
    assert gen["thinkingConfig"] == {"thinkingBudget": 0}
    assert gen["maxOutputTokens"] == min(1024, 2 * estimate_tokens(payload) + 64)


@respx.mock
async def test_formal_mode_description_in_payload(cfg):
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=ok_response("Dear team.")))
    await clean(cfg, mode="formal")
    payload = json.loads(route.calls.last.request.content)["contents"][0]["parts"][0]["text"]
    assert "Mode: formal (Rewrite in a professional" in payload


@respx.mock
async def test_timeout(cfg):
    cfg.llm.timeout_s = 0.05

    async def slow(request):
        await asyncio.sleep(1)
        return httpx.Response(200, json=ok_response("late"))

    respx.post(URL).mock(side_effect=slow)
    with pytest.raises(CleanerError) as e:
        await clean(cfg)
    assert e.value.kind == "timeout"


@respx.mock
async def test_connect_error_is_offline(cfg):
    respx.post(URL).mock(side_effect=httpx.ConnectError("dns"))
    with pytest.raises(CleanerError) as e:
        await clean(cfg)
    assert e.value.kind == "offline"


@pytest.mark.parametrize("status, kind", [(429, "rate_limited"), (400, "auth"), (403, "auth"), (500, "error"), (503, "error")])
@respx.mock
async def test_http_errors(cfg, status, kind):
    respx.post(URL).mock(return_value=httpx.Response(status, json={"error": {}}))
    with pytest.raises(CleanerError) as e:
        await clean(cfg)
    assert e.value.kind == kind


@respx.mock
async def test_safety(cfg):
    respx.post(URL).mock(return_value=httpx.Response(200, json=ok_response("", finish="SAFETY")))
    with pytest.raises(CleanerError) as e:
        await clean(cfg)
    assert e.value.kind == "blocked"


@respx.mock
async def test_empty_candidates(cfg):
    respx.post(URL).mock(return_value=httpx.Response(200, json={"candidates": []}))
    with pytest.raises(CleanerError) as e:
        await clean(cfg)
    assert e.value.kind == "error"


@respx.mock
async def test_max_tokens_is_a_failure_not_partial_text(cfg):
    """Regression (Codex review #1): output cut off at maxOutputTokens was accepted, and could pass
    the validator's 40% length check while missing the end of the dictation."""
    respx.post(URL).mock(return_value=httpx.Response(200, json=ok_response("The meeting is", finish="MAX_TOKENS")))
    with pytest.raises(CleanerError) as e:
        await clean(cfg)
    assert e.value.kind == "truncated"


@respx.mock
async def test_echoed_tags_are_stripped(cfg):
    respx.post(URL).mock(return_value=httpx.Response(200, json=ok_response("<transcript>\nThe meeting is Friday.\n</transcript>")))
    assert (await clean(cfg)).text == "The meeting is Friday."


@respx.mock
async def test_thought_parts_ignored(cfg):
    data = ok_response("The meeting is Friday.")
    data["candidates"][0]["content"]["parts"].insert(0, {"text": "thinking...", "thought": True})
    respx.post(URL).mock(return_value=httpx.Response(200, json=data))
    assert (await clean(cfg)).text == "The meeting is Friday."


@pytest.mark.parametrize(
    "out, original, expected",
    [
        ('"Hello there."', "hello there", "Hello there."),
        ("“Hello there.”", "hello there", "Hello there."),
        ("`ls -la`", "ls -la", "ls -la"),
        ("```\nHello\n```", "hello", "Hello"),
        ('"Quoted," he said.', '"quoted," he said', '"Quoted," he said.'),
        ('"Keep quotes."', '"keep quotes"', '"Keep quotes."'),
    ],
)
def test_strip_echo(out, original, expected):
    assert strip_echo(out, original) == expected


@respx.mock
async def test_warm_uses_metadata_call(cfg):
    route = respx.get(f"{GEMINI_BASE}/models/gemini-3.5-flash-lite").mock(return_value=httpx.Response(200, json={}))
    cleaner = GeminiCleaner(cfg, "k")
    assert cleaner.needs_warm()
    await cleaner.warm()
    assert route.called
    assert not cleaner.needs_warm()
    await cleaner.aclose()


def test_never_used_cleaner_needs_warm_right_after_boot(cfg, monkeypatch):
    """Regression: _last_used started at 0.0, and monotonic time is seconds since boot, so within
    five minutes of boot a never-connected cleaner claimed it was warm (seen on fresh CI runners)."""
    import murmur.cleaner as cleaner_mod

    monkeypatch.setattr(cleaner_mod.time, "monotonic", lambda: 42.0)  # 42 s after boot
    assert GeminiCleaner(cfg, "k").needs_warm()


@respx.mock
@pytest.mark.parametrize("body", [b"<html>502 Bad Gateway</html>", b"[1, 2]", b'{"status": "ok", "text": 42}'])
async def test_proxy_malformed_response_is_a_cleaner_error(cfg, body):
    """Regression (Codex review #3): invalid JSON or a wrong shape from the proxy escaped as ValueError/
    AttributeError, which the daemon didn't catch, so the whole dictation was lost."""
    from murmur.cleaner import ProxyCleaner

    cfg.llm.proxy_url = "https://proxy.example/v1/clean"
    respx.post(cfg.llm.proxy_url).mock(return_value=httpx.Response(200, content=body))
    cleaner = ProxyCleaner(cfg, "tok")
    try:
        with pytest.raises(CleanerError) as e:
            await cleaner.clean("hello there", mode="default", instructions="", bias_terms=[])
        assert e.value.kind == "error"
    finally:
        await cleaner.aclose()
