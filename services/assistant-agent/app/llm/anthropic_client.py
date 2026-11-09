"""Anthropic adapter: AsyncAnthropic + messages.stream with tools (spec 9.3)."""
from __future__ import annotations

from typing import Any

import anthropic

from .base import (MAX_TOKENS, MAX_TOOL_ROUNDS, TEMPERATURE, CallTool, Gate, OnToken, ProviderError,
                   classify_status)


def to_provider_error(exc: Exception) -> ProviderError:
    if isinstance(exc, anthropic.APITimeoutError):
        return ProviderError(str(exc), retryable=True, timeout=True)
    if isinstance(exc, anthropic.APIStatusError):
        return ProviderError(f"{exc.status_code}: {exc}", retryable=classify_status(exc.status_code),
                             status=exc.status_code)
    if isinstance(exc, anthropic.APIConnectionError):
        return ProviderError(str(exc), retryable=True)
    return ProviderError(repr(exc), retryable=False)


class AnthropicClient:
    name = "anthropic"

    def __init__(self, api_key: str, model: str, client: anthropic.AsyncAnthropic | None = None):
        # max_retries=0: retry policy is ours (one fallback), and the rate limiter must see every call.
        self.client = client or anthropic.AsyncAnthropic(api_key=api_key, max_retries=0, timeout=30.0)
        self.model = model

    async def run(self, system: str, history: list[dict[str, Any]], tools: list[dict[str, Any]],
                  on_token: OnToken, call_tool: CallTool, gate: Gate) -> None:
        system_blocks = [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}]
        messages: list[dict[str, Any]] = [dict(m) for m in history]
        for round_no in range(MAX_TOOL_ROUNDS):
            await gate()
            kwargs: dict[str, Any] = {}
            if round_no == MAX_TOOL_ROUNDS - 1:
                kwargs["tool_choice"] = {"type": "none"}  # last round: force a textual answer
            try:
                async with self.client.messages.stream(
                    model=self.model, max_tokens=MAX_TOKENS, temperature=TEMPERATURE,
                    system=system_blocks, messages=messages, tools=tools, **kwargs,
                ) as stream:
                    async for text in stream.text_stream:
                        await on_token(text)
                    final = await stream.get_final_message()
            except Exception as exc:  # noqa: BLE001 - translated to ProviderError
                raise to_provider_error(exc) from exc
            if final.stop_reason != "tool_use":
                return
            assistant_blocks: list[dict[str, Any]] = []
            results: list[dict[str, Any]] = []
            for block in final.content:
                if block.type == "text":
                    assistant_blocks.append({"type": "text", "text": block.text})
                elif block.type == "tool_use":
                    assistant_blocks.append(
                        {"type": "tool_use", "id": block.id, "name": block.name, "input": block.input})
                    out = await call_tool(block.name, block.input if isinstance(block.input, dict) else {})
                    results.append({"type": "tool_result", "tool_use_id": block.id, "content": out})
            messages.append({"role": "assistant", "content": assistant_blocks})
            messages.append({"role": "user", "content": results})
