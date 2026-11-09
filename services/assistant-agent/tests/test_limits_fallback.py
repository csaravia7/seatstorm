import asyncio
import json

import httpx
import pytest

from app.agent import FALLBACK_TEXT, SLOW_TEXT
from app.limits import LimiterTimeout, SessionLimiter, TokenBucket
from conftest import ANTHROPIC_URL, NVIDIA_URL, anthropic_text, openai_text, tokens


async def test_global_limiter_40_concurrent_with_rpm_30(app_factory, mock):
    route = mock.post(ANTHROPIC_URL).mock(side_effect=lambda r: anthropic_text("respuesta"))
    async with app_factory(llm_max_rpm=30, queue_timeout_s=0.3) as (chat, _):
        results = await asyncio.gather(*[chat.events("hola", session=f"s{i}", buyer=f"b{i}") for i in range(40)])
    answered = [r for r in results if tokens(r) == "respuesta"]
    degraded = [r for r in results if tokens(r) == FALLBACK_TEXT]
    assert route.call_count == 30                      # provider never sees more than the limit
    assert len(answered) == 30 and len(degraded) == 10
    assert all(r[-1][0] == "done" for r in results)


async def test_bucket_unit_waits_then_refuses():
    t = [0.0]
    b = TokenBucket(60, clock=lambda: t[0])  # 1 token/s
    for _ in range(60):
        await b.acquire(0)
    with pytest.raises(LimiterTimeout):
        await b.acquire(0.5)
    t[0] += 1.0
    await b.acquire(0)


def test_session_limiter_window():
    t = [0.0]
    lim = SessionLimiter(2, 3600, clock=lambda: t[0])
    assert lim.allow("a") and lim.allow("a") and not lim.allow("a")
    t[0] = 3601
    assert lim.allow("a")


@pytest.mark.parametrize("status", [429, 500, 503])
async def test_fallback_provider_on_retryable_status(app_factory, mock, status):
    primary = mock.post(ANTHROPIC_URL).mock(return_value=httpx.Response(status, json={
        "type": "error", "error": {"type": "rate_limit_error", "message": "slow down"}}))
    backup = mock.post(NVIDIA_URL).mock(return_value=openai_text("Desde ", "NVIDIA"))
    async with app_factory(llm_provider="anthropic", llm_fallback_provider="nvidia") as (chat, _):
        ev = await chat.events()
    assert primary.call_count == 1 and backup.call_count == 1   # no SDK retries, exactly one fallback
    assert tokens(ev) == "Desde NVIDIA" and ev[-1][0] == "done"


async def test_fallback_from_nvidia_to_anthropic_on_timeout(app_factory, mock):
    mock.post(NVIDIA_URL).mock(side_effect=httpx.ConnectTimeout("t"))
    mock.post(ANTHROPIC_URL).mock(return_value=anthropic_text("Respaldo"))
    async with app_factory(llm_provider="nvidia", llm_fallback_provider="anthropic") as (chat, _):
        ev = await chat.events()
    assert tokens(ev) == "Respaldo"


async def test_429_without_fallback_emits_error_event(app_factory, mock):
    mock.post(ANTHROPIC_URL).mock(return_value=httpx.Response(429, json={
        "type": "error", "error": {"type": "rate_limit_error", "message": "x"}}))
    async with app_factory() as (chat, _):
        ev = await chat.events()
    assert [e for e, _ in ev] == ["error"] and ev[0][1] == FALLBACK_TEXT


async def test_non_retryable_error_does_not_use_fallback(app_factory, mock):
    mock.post(ANTHROPIC_URL).mock(return_value=httpx.Response(400, json={
        "type": "error", "error": {"type": "invalid_request_error", "message": "bad"}}))
    backup = mock.post(NVIDIA_URL).mock(return_value=openai_text("no"))
    async with app_factory(llm_fallback_provider="nvidia") as (chat, _):
        ev = await chat.events()
    assert ev[0][0] == "error" and backup.call_count == 0


async def test_no_first_token_in_time_emits_slow_error(app_factory, mock):
    async def slow(request):
        await asyncio.sleep(2)
        return anthropic_text("tarde")
    mock.post(ANTHROPIC_URL).mock(side_effect=slow)
    async with app_factory(first_token_timeout_s=0.2) as (chat, _):
        ev = await chat.events()
    assert ev == [("error", SLOW_TEXT)]


async def test_first_token_timeout_triggers_fallback_provider(app_factory, mock):
    async def slow(request):
        await asyncio.sleep(2)
        return anthropic_text("tarde")
    mock.post(ANTHROPIC_URL).mock(side_effect=slow)
    mock.post(NVIDIA_URL).mock(return_value=openai_text("rápido"))
    async with app_factory(first_token_timeout_s=0.2, llm_fallback_provider="nvidia") as (chat, _):
        ev = await chat.events()
    assert tokens(ev) == "rápido" and ev[-1][0] == "done"


async def test_failed_turn_is_not_remembered(app_factory, mock):
    route = mock.post(ANTHROPIC_URL).mock(side_effect=[
        httpx.Response(500, json={"type": "error", "error": {"type": "api_error", "message": "x"}}),
        anthropic_text("bien")])
    async with app_factory() as (chat, _):
        assert (await chat.events("uno"))[0][0] == "error"
        await chat.events("dos")
    msgs = json.loads(route.calls[1].request.content)["messages"]
    assert [m["content"] for m in msgs] == ["dos"]
