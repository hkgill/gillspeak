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
async def test_max_tokens_is_accepted(cfg):
    respx.post(URL).mock(return_value=httpx.Response(200, json=ok_response("Partial text", finish="MAX_TOKENS")))
    assert (await clean(cfg)).text == "Partial text"


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
