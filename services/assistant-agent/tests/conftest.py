from __future__ import annotations

import contextlib
import json
from pathlib import Path

import httpx
import pytest
import respx

from app.config import Settings
from app.main import create_app

REPO_ROOT = Path(__file__).resolve().parents[3]
SEED_DOCS = REPO_ROOT / "seed" / "docs"
QUERY = "http://seatmap-query:8080"
ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
NVIDIA_URL = "https://integrate.api.nvidia.com/v1/chat/completions"


def make_settings(**overrides) -> Settings:
    base = dict(
        llm_provider="anthropic", llm_fallback_provider="", llm_max_rpm=1000,
        anthropic_api_key="sk-ant-test", nvidia_api_key="nv-test",
        query_url=QUERY, docs_dir=str(SEED_DOCS),
        queue_timeout_s=0.2, first_token_timeout_s=1.0, total_timeout_s=5.0,
    )
    base.update(overrides)
    return Settings(**base)


# ---- SSE builders for the provider APIs ------------------------------------------------------

def _sse(event: str | None, data) -> str:
    head = f"event: {event}\n" if event else ""
    return f"{head}data: {data if isinstance(data, str) else json.dumps(data)}\n\n"


def anthropic_sse(blocks: list[dict], stop_reason: str = "end_turn") -> httpx.Response:
    """blocks: {"text": [chunks]} or {"tool": (id, name, input_dict)}."""
    out = [_sse("message_start", {"type": "message_start", "message": {
        "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-sonnet-5-5", "content": [],
        "stop_reason": None, "stop_sequence": None, "usage": {"input_tokens": 1, "output_tokens": 1}}})]
    for i, b in enumerate(blocks):
        if "text" in b:
            out.append(_sse("content_block_start", {"type": "content_block_start", "index": i,
                                                    "content_block": {"type": "text", "text": ""}}))
            for chunk in b["text"]:
                out.append(_sse("content_block_delta", {"type": "content_block_delta", "index": i,
                                                        "delta": {"type": "text_delta", "text": chunk}}))
        else:
            tid, name, inp = b["tool"]
            out.append(_sse("content_block_start", {"type": "content_block_start", "index": i, "content_block": {
                "type": "tool_use", "id": tid, "name": name, "input": {}}}))
            raw = json.dumps(inp)
            out.append(_sse("content_block_delta", {"type": "content_block_delta", "index": i, "delta": {
                "type": "input_json_delta", "partial_json": raw[: len(raw) // 2]}}))
            out.append(_sse("content_block_delta", {"type": "content_block_delta", "index": i, "delta": {
                "type": "input_json_delta", "partial_json": raw[len(raw) // 2:]}}))
        out.append(_sse("content_block_stop", {"type": "content_block_stop", "index": i}))
    out.append(_sse("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop_reason,
                                                                       "stop_sequence": None},
                                      "usage": {"output_tokens": 5}}))
    out.append(_sse("message_stop", {"type": "message_stop"}))
    return httpx.Response(200, headers={"content-type": "text/event-stream"}, content="".join(out))


def anthropic_text(*chunks: str) -> httpx.Response:
    return anthropic_sse([{"text": list(chunks)}])


def anthropic_tool(tid: str, name: str, inp: dict, preamble: str | None = None) -> httpx.Response:
    blocks = ([{"text": [preamble]}] if preamble else []) + [{"tool": (tid, name, inp)}]
    return anthropic_sse(blocks, "tool_use")


def _chunk(delta: dict, finish=None) -> str:
    return _sse(None, {"id": "c1", "object": "chat.completion.chunk", "created": 0, "model": "m",
                       "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]})


def openai_text(*chunks: str, reasoning: str | None = None) -> httpx.Response:
    out = [_chunk({"role": "assistant", "content": ""})]
    if reasoning:
        out.append(_chunk({"reasoning_content": reasoning}))
    out += [_chunk({"content": c}) for c in chunks]
    out += [_chunk({}, "stop"), _sse(None, "[DONE]")]
    return httpx.Response(200, headers={"content-type": "text/event-stream"}, content="".join(out))


def openai_tool(call_id: str, name: str, args: dict) -> httpx.Response:
    raw = json.dumps(args)
    half = len(raw) // 2
    out = [
        _chunk({"role": "assistant", "content": None, "tool_calls": [
            {"index": 0, "id": call_id, "type": "function", "function": {"name": name, "arguments": ""}}]}),
        _chunk({"tool_calls": [{"index": 0, "function": {"arguments": raw[:half]}}]}),
        _chunk({"tool_calls": [{"index": 0, "function": {"arguments": raw[half:]}}]}),
        _chunk({}, "tool_calls"), _sse(None, "[DONE]"),
    ]
    return httpx.Response(200, headers={"content-type": "text/event-stream"}, content="".join(out))


# ---- app client ------------------------------------------------------------------------------

def parse_sse(text: str) -> list[tuple[str, str]]:
    events = []
    for block in text.split("\n\n"):
        if not block.strip("\n"):
            continue
        name, data = "message", []
        for line in block.split("\n"):
            if line.startswith("event: "):
                name = line[7:]
            elif line.startswith("data: "):
                data.append(line[6:])
            elif line == "data:":
                data.append("")
        events.append((name, "\n".join(data)))
    return events


def tokens(events) -> str:
    return "".join(d for e, d in events if e == "token")


class Chat:
    def __init__(self, http: httpx.AsyncClient):
        self.http = http

    async def post(self, message="hola", buyer="b_1", session="s_1", raw=None):
        headers = {"X-Buyer-Id": buyer, "X-Session-Id": session}
        if raw is not None:
            return await self.http.post("/api/chat", content=raw, headers={**headers, "content-type": "application/json"})
        return await self.http.post("/api/chat", json={"message": message}, headers=headers)

    async def events(self, *a, **kw):
        r = await self.post(*a, **kw)
        assert r.status_code == 200, r.text
        return parse_sse(r.text)


@contextlib.asynccontextmanager
async def running_app(settings: Settings):
    app = create_app(lambda: settings)
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://agent") as http:
            yield Chat(http), app


@pytest.fixture
def mock():
    with respx.mock(assert_all_called=False) as m:
        yield m


@pytest.fixture
def app_factory():
    @contextlib.asynccontextmanager
    async def factory(**overrides):
        async with running_app(make_settings(**overrides)) as pair:
            yield pair
    return factory
