"""Orchestration: global limiter, provider chain with one fallback, first-token watchdog."""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from typing import AsyncIterator

import httpx
from opentelemetry.trace import Status, StatusCode

from . import telemetry as T
from .config import Settings
from .guardrails import REFUSALS, OutputGuard, check_input
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
                 clients: dict[str, LlmClient] | None = None, tracer=None):
        self.tracer = tracer or T.Telemetry().tracer
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

    def _tools_for(self, name: str) -> list[dict]:
        admin = self.s.admin_tools
        return anthropic_tools(admin) if name == "anthropic" else openai_tools(admin)

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
        turn: dict = {"outcome": "ok", "output": "", "guardrails": [], "input": message,
                      "input_verdict": None, "output_verdicts": [], "context": ""}
        with self.tracer.start_as_current_span("assistant.chat", attributes={
                T.SPAN_KIND: "AGENT", T.SESSION_ID: sid, T.USER_ID: session.buyer_id,
                T.INPUT_VALUE: message}) as span:
            turn["span"] = span
            try:
                await self._produce_inner(q, session, message, turn)
            except Exception as exc:  # noqa: BLE001 - never let the stream hang
                log.exception("chat_unexpected_error", extra={"sessionId": sid})
                turn["outcome"], turn["output"] = "error", SLOW_TEXT
                span.record_exception(exc)
                await q.put(("error", SLOW_TEXT))
            finally:
                try:
                    self._finish_turn_span(span, turn)
                except Exception:  # noqa: BLE001 - telemetry must never break (or hang) the chat
                    log.warning("turn_span_attributes_failed", exc_info=True)
                await q.put(None)

    def _finish_turn_span(self, span, turn: dict) -> None:
        span.set_attributes({
            T.INPUT_VALUE: turn["input"], T.OUTPUT_VALUE: turn["output"],
            "assistant.outcome": turn["outcome"],
            T.METADATA: json.dumps({"outcome": turn["outcome"], "guardrails_mode": self.s.guardrails_mode,
                                    "guardrails_triggered": turn["guardrails"],
                                    "provider": self.s.llm_provider}),
        })
        span.set_attributes(T.evaluation_attributes(T.build_evaluations(turn, self.s.guardrails_mode)))
        if turn["context"]:
            span.set_attribute(T.CONTEXT, turn["context"][:T.MAX_CONTEXT_CHARS])
        if turn["outcome"] in ("error", "provider_error"):
            span.set_status(Status(StatusCode.ERROR, turn["outcome"]))

    def _guardrail_span(self, name: str, attrs: dict) -> None:
        """Short GUARDRAIL span so each check shows up (pass or fail) in the trace and can feed Arize evals."""
        with self.tracer.start_as_current_span(name) as gs:
            gs.set_attributes({T.SPAN_KIND: "GUARDRAIL", **attrs})

    async def _produce_inner(self, q: asyncio.Queue, session: Session, message: str, turn: dict) -> None:
        sid = session.session_id
        if not self.chain:
            log.warning("chat_no_provider_key", extra={"sessionId": sid})
            turn["outcome"], turn["output"] = "no_provider", FALLBACK_TEXT
            await q.put(("token", FALLBACK_TEXT))
            await q.put(("done", ""))
            return

        mode = self.s.guardrails_mode
        if mode != "off":
            verdict = check_input(message)
            if verdict:
                log.warning("guardrail_triggered", extra={
                    "sessionId": sid, "guardrail": verdict.guardrail, "direction": "input",
                    "action": "block" if mode == "enforce" else "flag", "mode": mode, "detail": verdict.detail})
                turn["guardrails"].append(f"input:{verdict.guardrail}")
                turn["input_verdict"] = verdict
                if verdict.guardrail == "pii_leakage":
                    turn["input"] = T.REDACTED  # never ship the sensitive text to the observability backend
            self._guardrail_span("guardrail.input", {
                T.INPUT_VALUE: turn["input"], "guardrail.mode": mode, "guardrail.triggered": bool(verdict),
                "guardrail.name": verdict.guardrail if verdict else "none",
                "guardrail.action": ("block" if mode == "enforce" else "flag") if verdict else "pass",
                "guardrail.detail": verdict.detail if verdict else ""})
            if verdict and mode == "enforce":
                # Blocked before the model: no LLM call, and the message is NOT stored in the history.
                turn["outcome"], turn["output"] = f"blocked:{verdict.guardrail}", REFUSALS[verdict.guardrail]
                await q.put(("token", REFUSALS[verdict.guardrail]))
                await q.put(("done", ""))
                return

        history = list(session.history) + [{"role": "user", "content": message}]
        parts: list[str] = []
        first = asyncio.Event()
        guard = OutputGuard(self.system, enforce=mode == "enforce", sid=sid) if mode != "off" else None
        tool_outputs: list[str] = []

        async def on_token(t: str) -> None:
            if not t:
                return
            parts.append(t)
            first.set()  # first-token watchdog follows the model, not the (sentence-buffered) output
            for piece in (guard.feed(t) if guard else [t]):
                await q.put(("token", piece))

        runner = ToolRunner(self.http, self.s.query_url, self.s.concert_id, session.buyer_id,
                            self.s.tool_timeout_s, sid, nickname=session.nickname, order_url=self.s.order_url,
                            catalog_url=self.s.catalog_url, payment_url=self.s.payment_url,
                            bots_url=self.s.bots_url, admin_token=self.s.admin_token,
                            admin_tools=self.s.admin_tools)

        async def call_tool(name: str, args: dict | None) -> str:
            with self.tracer.start_as_current_span(f"tool.{name}", attributes={
                    T.SPAN_KIND: "TOOL", T.TOOL_NAME: name,
                    T.INPUT_VALUE: json.dumps(args or {}, ensure_ascii=False)}) as ts:
                out = await runner.call(name, args)
                ts.set_attribute(T.OUTPUT_VALUE, out)
            tool_outputs.append(out)
            while runner.actions:  # UI actions (e.g. navigate to /reservar after reserving) go to the browser as SSE
                await q.put(("action", json.dumps(runner.actions.pop(0))))
            return out

        for i, name in enumerate(self.chain):
            client = self.clients[name]
            try:
                await self._attempt(client, history, on_token, call_tool, first)
                break
            except LimiterTimeout:
                log.warning("limiter_timeout", extra={"sessionId": sid, "provider": name})
                turn["outcome"], turn["output"] = "limiter_timeout", FALLBACK_TEXT
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
                    text = SLOW_TEXT if exc.timeout else FALLBACK_TEXT
                    turn["outcome"], turn["output"] = "provider_error", text
                    await q.put(("error", text))
                    return
                log.info("fallback_provider", extra={"sessionId": sid, "from": name, "to": self.chain[i + 1]})

        if guard:
            for piece in guard.finish(message + " " + " ".join(tool_outputs)):
                await q.put(("token", piece))
            names = sorted({f"{v.direction}:{v.guardrail}" for v in guard.verdicts})
            turn["guardrails"] += names
            turn["output_verdicts"] = list(guard.verdicts)
            self._guardrail_span("guardrail.output", {
                T.OUTPUT_VALUE: guard.text, "guardrail.mode": mode, "guardrail.triggered": bool(guard.verdicts),
                "guardrail.names": names or ["none"],
                "guardrail.blocked": bool(guard.blocked),
                "guardrail.hallucination_suspect": any(v.guardrail == "hallucination" for v in guard.verdicts)})
            if guard.blocked:
                turn["outcome"] = f"blocked:{guard.blocked.guardrail}"
        turn["context"] = "\n".join(tool_outputs)
        final = guard.text if guard else "".join(parts)
        turn["output"] = final
        session.add_turn(message, final)
        await q.put(("done", ""))

    async def _attempt(self, client: LlmClient, history: list[dict], on_token, call_tool,
                       first: asyncio.Event) -> None:
        gated = asyncio.Event()
        raw_gate = self._gate

        # Wrap the gate so the first-token timer starts once the limiter granted a turn.
        async def gate() -> None:
            await raw_gate()
            gated.set()

        task = asyncio.create_task(
            client.run(self.system, history, self._tools_for(client.name), on_token, call_tool, gate))
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
