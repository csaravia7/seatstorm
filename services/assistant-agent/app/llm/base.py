"""Provider-neutral protocol and error taxonomy."""
from __future__ import annotations

from typing import Any, Awaitable, Callable, Protocol

OnToken = Callable[[str], Awaitable[None]]
CallTool = Callable[[str, dict], Awaitable[str]]
Gate = Callable[[], Awaitable[None]]  # global rate limiter turn; raises LimiterTimeout

MAX_TOOL_ROUNDS = 4
MAX_TOKENS = 400
TEMPERATURE = 0.2


class ProviderError(Exception):
    """Provider call failed. `retryable` = 429 / 5xx / timeout / connection (fallback eligible)."""

    def __init__(self, message: str, *, retryable: bool, timeout: bool = False, status: int | None = None):
        super().__init__(message)
        self.retryable = retryable
        self.timeout = timeout
        self.status = status


class LlmClient(Protocol):
    name: str

    async def run(self, system: str, history: list[dict[str, Any]], tools: list[dict[str, Any]],
                  on_token: OnToken, call_tool: CallTool, gate: Gate) -> None:
        """Stream the answer through on_token, running up to MAX_TOOL_ROUNDS tool rounds.

        `gate` is awaited before every provider call (global rate limit).
        `history` is provider-neutral text turns and is NOT mutated.
        """
        ...


def classify_status(status: int) -> bool:
    return status == 429 or status >= 500
