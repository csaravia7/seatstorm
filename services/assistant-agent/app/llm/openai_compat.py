"""OpenAI-compatible adapter (NVIDIA NIM via base_url) with streamed tool_calls."""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

import openai

from .base import (MAX_TOOL_ROUNDS, CallTool, Gate, OnToken, ProviderError,
                   classify_status)

log = logging.getLogger(__name__)


def to_provider_error(exc: Exception) -> ProviderError:
    if isinstance(exc, openai.APITimeoutError):
        return ProviderError(str(exc), retryable=True, timeout=True)
    if isinstance(exc, openai.APIStatusError):
        return ProviderError(f"{exc.status_code}: {exc}", retryable=classify_status(exc.status_code),
                             status=exc.status_code)
    if isinstance(exc, openai.APIConnectionError):
        return ProviderError(str(exc), retryable=True)
    return ProviderError(repr(exc), retryable=False)


def _parse_args(raw: str) -> dict:
    try:
        val = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        return {}
    return val if isinstance(val, dict) else {}


class OpenAICompatClient:
    name = "nvidia"

    def __init__(self, api_key: str, base_url: str, model: str,
                 client: openai.AsyncOpenAI | None = None, temperature: float = 1.0, top_p: float = 1.0,
                 max_tokens: int = 4096):
        self.client = client or openai.AsyncOpenAI(api_key=api_key, base_url=base_url, max_retries=0, timeout=30.0)
        self.model = model
        self.temperature = temperature
        self.top_p = top_p
        self.max_tokens = max_tokens

    async def run(self, system: str, history: list[dict[str, Any]], tools: list[dict[str, Any]],
                  on_token: OnToken, call_tool: CallTool, gate: Gate) -> None:
        messages: list[dict[str, Any]] = [{"role": "system", "content": system}] + [dict(m) for m in history]
        for round_no in range(MAX_TOOL_ROUNDS):
            await gate()
            kwargs: dict[str, Any] = {}
            if round_no == MAX_TOOL_ROUNDS - 1:
                kwargs["tool_choice"] = "none"
            text_parts: list[str] = []
            reasoning_chars = 0
            calls: dict[int, dict[str, str]] = {}
            try:
                stream = await self.client.chat.completions.create(
                    model=self.model, messages=messages, tools=tools, stream=True,
                    max_tokens=self.max_tokens, temperature=self.temperature, top_p=self.top_p, **kwargs)
                async for chunk in stream:
                    if not chunk.choices:
                        continue
                    delta = chunk.choices[0].delta
                    # Only `content` is streamed. Reasoning (chain-of-thought, e.g. gpt-oss `reasoning_content`)
                    # is never forwarded to users; only its length is counted for logging.
                    reasoning = getattr(delta, "reasoning_content", None) or getattr(delta, "reasoning", None)
                    if isinstance(reasoning, str):
                        reasoning_chars += len(reasoning)
                    if delta.content:
                        text_parts.append(delta.content)
                        await on_token(delta.content)
                    for tc in delta.tool_calls or []:
                        slot = calls.setdefault(tc.index, {"id": "", "name": "", "arguments": ""})
                        if tc.id:
                            slot["id"] = tc.id
                        if tc.function:
                            if tc.function.name:
                                slot["name"] += tc.function.name
                            if tc.function.arguments:
                                slot["arguments"] += tc.function.arguments
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                raise to_provider_error(exc) from exc
            if reasoning_chars:
                log.debug("nvidia reasoning discarded: %d chars", reasoning_chars)
            if not calls:
                return
            ordered = [calls[i] for i in sorted(calls)]
            for n, c in enumerate(ordered):
                c["id"] = c["id"] or f"call_{round_no}_{n}"
            messages.append({
                "role": "assistant",
                "content": "".join(text_parts) or None,
                "tool_calls": [{"id": c["id"], "type": "function",
                                "function": {"name": c["name"], "arguments": c["arguments"] or "{}"}}
                               for c in ordered],
            })
            for c in ordered:
                out = await call_tool(c["name"], _parse_args(c["arguments"]))
                messages.append({"role": "tool", "tool_call_id": c["id"], "content": out})
