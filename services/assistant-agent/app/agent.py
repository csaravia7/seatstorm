"""Orchestration: global limiter, provider chain with one fallback, first-token watchdog."""
from __future__ import annotations

import asyncio
import contextlib
import logging
from typing import AsyncIterator

import httpx

from .config import Settings
from .limits import LimiterTimeout, TokenBucket
from .llm.anthropic_client import AnthropicClient
from .llm.base import LlmClient, ProviderError
from .llm.openai_compat import OpenAICompatClient
from .sessions import Session
from .tools import ToolRunner, anthropic_tools, openai_tools

log = logging.getLogger("assistant.agent")

FALLBACK_TEXT = (
    "Ahora mismo no puedo responder con la IA, pero la venta sigue funcionando con normalidad. "
    "Usa la pantalla de compra o vuelve a preguntarme en unos minutos."
)
SLOW_TEXT = "Estoy tardando más de lo normal. Prueba de nuevo en unos segundos."


class Agent:
    def __init__(self, settings: Settings, system_prompt: str, http: httpx.AsyncClient,
                 clients: dict[str, LlmClient] | None = None):
        self.s = settings
        self.system = system_prompt
        self.http = http
        self.bucket = TokenBucket(settings.llm_max_rpm)
        self.chain = settings.provider_chain()
        self.clients: dict[str, LlmClient] = clients if clients is not None else self._build_clients()

    async def _gate(self) -> None:
        await self.bucket.acquire(self.s.queue_timeout_s)

    def _build_clients(self) -> dict[str, LlmClient]:
        out: dict[str, LlmClient] = {}
        for name in self.chain:
            if name == "anthropic":
                out[name] = AnthropicClient(self.s.anthropic_api_key, self.s.anthropic_model, None)
            else:
                out[name] = OpenAICompatClient(self.s.nvidia_api_key, self.s.nvidia_base_url,
                                               self.s.nvidia_model, temperature=self.s.nvidia_temperature,
                                               top_p=self.s.nvidia_top_p, max_tokens=self.s.nvidia_max_tokens)
        return out

    @staticmethod
    def _tools_for(name: str) -> list[dict]:
        return anthropic_tools() if name == "anthropic" else openai_tools()

    async def events(self, session: Session, message: str) -> AsyncIterator[tuple[str, str]]:
        """Yield (event, data) SSE tuples: token* then done | error."""
        queue: asyncio.Queue = asyncio.Queue()
        producer = asyncio.create_task(self._produce(queue, session, message))
        try:
            while True:
                item = await queue.get()
                if item is None:
                    return
                yield item
        finally:
            if not producer.done():
                producer.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await producer

    async def _produce(self, q: asyncio.Queue, session: Session, message: str) -> None:
        sid = session.session_id
        try:
            await self._produce_inner(q, session, message)
        except Exception:  # noqa: BLE001 - never let the stream hang
            log.exception("chat_unexpected_error", extra={"sessionId": sid})
            await q.put(("error", SLOW_TEXT))
        finally:
            await q.put(None)

    async def _produce_inner(self, q: asyncio.Queue, session: Session, message: str) -> None:
        sid = session.session_id
        if not self.chain:
            log.warning("chat_no_provider_key", extra={"sessionId": sid})
            await q.put(("token", FALLBACK_TEXT))
            await q.put(("done", ""))
            return

        history = list(session.history) + [{"role": "user", "content": message}]
        parts: list[str] = []
        first = asyncio.Event()

        async def on_token(t: str) -> None:
            if not t:
                return
            parts.append(t)
            first.set()
            await q.put(("token", t))

        runner = ToolRunner(self.http, self.s.query_url, self.s.concert_id, session.buyer_id,
                            self.s.tool_timeout_s, sid)

        for i, name in enumerate(self.chain):
            client = self.clients[name]
            try:
                await self._attempt(client, history, on_token, runner, first)
                break
            except LimiterTimeout:
                log.warning("limiter_timeout", extra={"sessionId": sid, "provider": name})
                if parts:
                    await q.put(("error", FALLBACK_TEXT))
                else:
                    await q.put(("token", FALLBACK_TEXT))
                    await q.put(("done", ""))
                return
            except ProviderError as exc:
                log.warning("provider_error", extra={"sessionId": sid, "provider": name, "error": str(exc),
                                                     "status": exc.status, "timeout": exc.timeout})
                last = i == len(self.chain) - 1
                if parts or not exc.retryable or last:
                    await q.put(("error", SLOW_TEXT if exc.timeout else FALLBACK_TEXT))
                    return
                log.info("fallback_provider", extra={"sessionId": sid, "from": name, "to": self.chain[i + 1]})

        session.add_turn(message, "".join(parts))
        await q.put(("done", ""))

    async def _attempt(self, client: LlmClient, history: list[dict], on_token, runner: ToolRunner,
                       first: asyncio.Event) -> None:
        gated = asyncio.Event()
        raw_gate = self._gate

        # Wrap the gate so the first-token timer starts once the limiter granted a turn.
        async def gate() -> None:
            await raw_gate()
            gated.set()

        task = asyncio.create_task(
            client.run(self.system, history, self._tools_for(client.name), on_token, runner.call, gate))
        try:
            await self._wait_any(task, gated, None)
            if not task.done():
                if not await self._wait_any(task, first, self.s.first_token_timeout_s):
                    raise ProviderError("no first token", retryable=True, timeout=True)
            if not task.done():
                done, _ = await asyncio.wait({task}, timeout=self.s.total_timeout_s)
                if not done:
                    raise ProviderError("total timeout", retryable=True, timeout=True)
            task.result()
        except BaseException:
            if not task.done():
                task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
            raise

    @staticmethod
    async def _wait_any(task: asyncio.Task, event: asyncio.Event, timeout: float | None) -> bool:
        """True if `event` fired (or task finished) before timeout."""
        waiter = asyncio.create_task(event.wait())
        try:
            done, _ = await asyncio.wait({task, waiter}, timeout=timeout, return_when=asyncio.FIRST_COMPLETED)
            return bool(done)
        finally:
            waiter.cancel()
