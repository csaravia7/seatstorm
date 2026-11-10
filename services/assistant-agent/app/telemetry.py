"""Arize AX tracing via OpenInference (OpenTelemetry). Optional: without ARIZE_SPACE_ID and ARIZE_API_KEY it is a no-op.

What reaches Arize:
  * every `anthropic` / `openai` SDK call as an LLM span (OpenInference auto-instrumentation: messages, tokens, model),
  * one root AGENT span per chat turn with input.value / output.value, session.id and user.id (so Arize evaluators such
    as Relevance, Hallucination, Conciseness and User Frustration can score the turn and the conversation),
  * a GUARDRAIL span per turn (input) and one for the output, with guardrail.* attributes,
  * a TOOL span per tool call.

Privacy: messages blocked for PII are recorded redacted, never verbatim.
Dynatrace OneAgent also hooks these SDKs; if spans are duplicated there, set ARIZE_ENABLED=false (see docs/DECISIONS.md).
"""
from __future__ import annotations

import logging
from typing import Any

from opentelemetry import trace

from .config import Settings

log = logging.getLogger("assistant.telemetry")

TRACER_NAME = "seatstorm.assistant-agent"
REDACTED = "[REDACTED: datos sensibles]"

# OpenInference semantic conventions (plain strings, so the app does not depend on the semconv package layout).
SPAN_KIND = "openinference.span.kind"
INPUT_VALUE = "input.value"
OUTPUT_VALUE = "output.value"
SESSION_ID = "session.id"
USER_ID = "user.id"
TOOL_NAME = "tool.name"
TOOL_PARAMS = "tool.parameters"
METADATA = "metadata"


class Telemetry:
    """Holds the tracer provider so it can be flushed on shutdown."""

    def __init__(self, provider: Any = None):
        self.provider = provider
        self.enabled = provider is not None

    @property
    def tracer(self) -> trace.Tracer:
        if self.provider is not None:
            return self.provider.get_tracer(TRACER_NAME)
        return trace.get_tracer(TRACER_NAME)  # API no-op tracer when nothing is configured

    def shutdown(self) -> None:
        if self.provider is None:
            return
        try:
            self.provider.force_flush(5000)
            self.provider.shutdown()
        except Exception:  # noqa: BLE001 - telemetry must never break shutdown
            log.warning("telemetry_shutdown_failed", exc_info=True)


def _tolerate_arize_details_bug() -> None:
    """arize-otel's register() always calls TracerProvider._tracing_details() (a cosmetic summary) which reads
    `exporter._headers`; with opentelemetry-exporter-otlp-proto-http >= 1.45 that attribute is gone and register()
    crashes with AttributeError. The summary is not needed, so make it fail-safe. Guarded by tests/test_telemetry.py."""
    from arize.otel import otel as arize_otel

    original = arize_otel.TracerProvider._tracing_details
    if getattr(original, "_seatstorm_safe", False):
        return

    def safe(self: Any) -> str:
        try:
            return original(self)
        except Exception:  # noqa: BLE001
            return ""

    safe._seatstorm_safe = True  # type: ignore[attr-defined]
    arize_otel.TracerProvider._tracing_details = safe


def init_telemetry(settings: Settings) -> Telemetry:
    if not settings.arize_enabled:
        log.info("arize_tracing_disabled", extra={"reason": settings.arize_disabled_reason})
        return Telemetry()
    try:
        # Imported lazily: the SDKs are only touched when tracing is actually configured.
        from arize.otel import Transport, register
        from openinference.instrumentation.anthropic import AnthropicInstrumentor
        from openinference.instrumentation.openai import OpenAIInstrumentor

        _tolerate_arize_details_bug()
        provider = register(
            space_id=settings.arize_space_id,
            api_key=settings.arize_api_key,
            project_name=settings.arize_project_name,
            endpoint=settings.arize_endpoint,
            transport=Transport.HTTP,
            batch=True,
            set_global_tracer_provider=False,
            verbose=False,
        )
        OpenAIInstrumentor().instrument(tracer_provider=provider)
        AnthropicInstrumentor().instrument(tracer_provider=provider)
        log.info("arize_tracing_enabled", extra={"project": settings.arize_project_name,
                                                 "endpoint": settings.arize_endpoint})
        return Telemetry(provider)
    except Exception:  # noqa: BLE001 - a telemetry problem must never take the chat down
        log.warning("arize_tracing_init_failed", exc_info=True)
        return Telemetry()
