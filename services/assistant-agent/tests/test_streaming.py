import httpx

from conftest import (ANTHROPIC_URL, NVIDIA_URL, anthropic_text, openai_text, parse_sse, tokens)


async def test_healthz(app_factory):
    async with app_factory() as (chat, _):
        r = await chat.http.get("/healthz")
        assert r.status_code == 200 and r.json() == {"status": "ok"}


async def test_anthropic_stream_complete_and_in_order(app_factory, mock):
    route = mock.post(ANTHROPIC_URL).mock(return_value=anthropic_text("Hola, ", "te ", "ayudo."))
    async with app_factory(llm_provider="anthropic") as (chat, _):
        ev = await chat.events("¿cómo compro?")
    assert [e for e, _ in ev] == ["token", "token", "token", "done"]
    assert tokens(ev) == "Hola, te ayudo."
    body = route.calls[0].request.content
    assert b'"stream":true' in body.replace(b" ", b"") and b"tools" in body


async def test_nvidia_stream_complete_and_in_order_ignores_reasoning(app_factory, mock):
    route = mock.post(NVIDIA_URL).mock(return_value=openai_text("Hola, ", "te ", "ayudo.", reasoning="PENSANDO"))
    async with app_factory(llm_provider="nvidia") as (chat, _):
        ev = await chat.events()
    assert tokens(ev) == "Hola, te ayudo."
    assert "PENSANDO" not in str(ev)
    assert ev[-1][0] == "done"
    assert route.calls[0].request.headers["authorization"] == "Bearer nv-test"


async def test_message_over_500_chars_is_400(app_factory, mock):
    route = mock.post(ANTHROPIC_URL).mock(return_value=anthropic_text("x"))
    async with app_factory() as (chat, _):
        r = await chat.post("a" * 501)
        assert r.status_code == 400 and r.json()["code"] == "VALIDATION_ERROR"
        ok = await chat.post("a" * 500)
        assert ok.status_code == 200
        assert (await chat.post("")).status_code == 400
        assert (await chat.post(raw="not json")).status_code == 400
    assert route.call_count == 1


async def test_missing_headers(app_factory):
    async with app_factory() as (chat, _):
        r = await chat.http.post("/api/chat", json={"message": "hi"}, headers={"X-Session-Id": "s"})
        assert r.status_code == 401 and r.json()["code"] == "NO_SESSION"
        r = await chat.http.post("/api/chat", json={"message": "hi"}, headers={"X-Buyer-Id": "b"})
        assert r.status_code == 400


async def test_no_api_key_starts_and_answers_fallback_text(app_factory, mock):
    route = mock.post(ANTHROPIC_URL).mock(return_value=anthropic_text("nunca"))
    async with app_factory(anthropic_api_key="") as (chat, _):
        assert (await chat.http.get("/healthz")).status_code == 200
        ev = await chat.events()
    assert ev[-1][0] == "done" and "no puedo responder" in tokens(ev)
    assert route.call_count == 0


async def test_21st_message_in_an_hour_is_429(app_factory, mock):
    mock.post(ANTHROPIC_URL).mock(side_effect=lambda r: anthropic_text("ok"))
    async with app_factory() as (chat, _):
        for _ in range(20):
            assert (await chat.post()).status_code == 200
        r = await chat.post()
        assert r.status_code == 429 and r.json()["code"] == "RATE_LIMITED"
        assert (await chat.post(session="other")).status_code == 200


async def test_multiline_token_roundtrips_through_sse(app_factory, mock):
    mock.post(ANTHROPIC_URL).mock(return_value=anthropic_text("uno\ndos\n"))
    async with app_factory() as (chat, _):
        r = await chat.post()
    assert tokens(parse_sse(r.text)) == "uno\ndos\n"


async def test_conversation_memory_is_sent_back(app_factory, mock):
    route = mock.post(ANTHROPIC_URL).mock(side_effect=lambda r: anthropic_text("respuesta"))
    async with app_factory() as (chat, _):
        await chat.events("primera")
        await chat.events("segunda")
    import json
    msgs = json.loads(route.calls[1].request.content)["messages"]
    assert [m["role"] for m in msgs] == ["user", "assistant", "user"]
    assert msgs[0]["content"] == "primera"
