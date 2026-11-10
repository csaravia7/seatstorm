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


CONTEXT = "assistant.context"
MAX_CONTEXT_CHARS = 4000

# guardrail -> (evaluation name, label when triggered, label when clean)
EVALS = {
    "prompt_injection": ("guardrail.prompt_injection", "injection", "clean"),
    "pii_leakage": ("guardrail.pii_leakage", "leaked", "clean"),
    "toxicity": ("guardrail.toxicity", "toxic", "non_toxic"),
    "off_topic": ("guardrail.off_topic", "off_topic", "on_topic"),
    "hallucination": ("guardrail.hallucination", "suspect", "grounded"),
}
# Output-side "prompt_leak" is reported as part of prompt_injection (the model tried to disclose its instructions).
_ALIASES = {"prompt_leak": "prompt_injection"}


def build_evaluations(turn: dict, mode: str) -> list[dict]:
    """One code-based evaluation per guardrail for the turn (score 1.0 = triggered, 0.0 = clean).

    Names carry a `guardrail.` prefix so they never collide with the LLM-judge evaluators configured in Arize
    (Toxicity, Hallucination, PII Leakage, ...): the demo can compare rule-based vs judge-based results.
    Explanations never contain raw user text (PII kinds only)."""
    if mode == "off":
        return []
    hits: dict[str, list[dict]] = {k: [] for k in EVALS}
    v = turn.get("input_verdict")
    if v:
        hits[_ALIASES.get(v.guardrail, v.guardrail)].append({"direction": "input", "detail": v.detail})
    for ov in turn.get("output_verdicts", []):
        hits[_ALIASES.get(ov.guardrail, ov.guardrail)].append({"direction": "output", "detail": ov.detail})
    action = "block" if mode == "enforce" else "flag"
    out = []
    for key, (name, trig, clean) in EVALS.items():
        h = hits[key]
        ev: dict = {"name": name, "label": trig if h else clean, "score": 1.0 if h else 0.0,
                    "annotator_kind": "CODE", "identifier": f"guardrail:{key}",
                    "metadata": {"mode": mode, "action": action if h else "pass",
                                 "directions": sorted({x["direction"] for x in h})}}
        if h:
            ev["explanation"] = "; ".join(f'{x["direction"]}: {x["detail"]}' for x in h)[:300]
        out.append(ev)
    return out


def evaluation_attributes(evaluations: list[dict]) -> dict:
    """Span attributes for the evaluations, in two shapes so whichever the backend reads is present:
    OpenInference `evaluations.N.evaluation.*` and the flat `eval.<name>.{label,score,explanation}` convention."""
    if not evaluations:
        return {}
    from openinference.instrumentation import get_evaluation_attributes

    attrs: dict = dict(get_evaluation_attributes(evaluations=evaluations))
    for ev in evaluations:
        base = f'eval.{ev["name"]}'
        attrs[f"{base}.label"] = ev["label"]
        attrs[f"{base}.score"] = ev["score"]
        if ev.get("explanation"):
            attrs[f"{base}.explanation"] = ev["explanation"]
    return attrs


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


def traces_url(endpoint: str) -> str:
    """Only for ARIZE_TRANSPORT=http: OTLP/HTTP posts to the exact URL given and does not append the signal path, and
    `POST /v1` answers 200 "ok" while discarding the data (no auth check), so spans would vanish silently. The ingest
    route is `/v1/traces`. The default gRPC transport takes the documented endpoint (https://otlp.arize.com/v1) as is."""
    url = endpoint.strip().rstrip("/")
    return url if url.endswith("/traces") else url + "/traces"


def exporter_target(settings: Settings) -> tuple[Any, str]:
    """(Transport, endpoint) for register(). gRPC is Arize's default and uses ARIZE_OTLP_ENDPOINT unchanged."""
    from arize.otel import Transport

    if settings.arize_transport == "http":
        return Transport.HTTP, traces_url(settings.arize_endpoint)
    return Transport.GRPC, settings.arize_endpoint


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
        from arize.otel import register
        from openinference.instrumentation.anthropic import AnthropicInstrumentor
        from openinference.instrumentation.openai import OpenAIInstrumentor

        _tolerate_arize_details_bug()
        transport, endpoint = exporter_target(settings)
        provider = register(
            space_id=settings.arize_space_id,
            api_key=settings.arize_api_key,
            project_name=settings.arize_project_name,
            endpoint=endpoint,
            transport=transport,
            batch=True,
            set_global_tracer_provider=False,
            verbose=False,
        )
        OpenAIInstrumentor().instrument(tracer_provider=provider)
        AnthropicInstrumentor().instrument(tracer_provider=provider)
        log.info("arize_tracing_enabled", extra={"project": settings.arize_project_name,
                                                 "endpoint": endpoint, "transport": str(transport.value)})
        return Telemetry(provider)
    except Exception:  # noqa: BLE001 - a telemetry problem must never take the chat down
        log.warning("arize_tracing_init_failed", exc_info=True)
        return Telemetry()
