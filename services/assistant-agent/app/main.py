"""FastAPI app: POST /api/chat (SSE), GET /healthz. Run with ONE uvicorn worker (spec 9.6)."""
from __future__ import annotations

import contextlib
import logging
from typing import Callable

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from .agent import Agent
from .config import Settings
from .limits import SessionLimiter
from .logging_setup import setup_logging
from .prompt import build_system_prompt
from .sessions import SessionStore

log = logging.getLogger("assistant.main")

MAX_MESSAGE_CHARS = 500


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=MAX_MESSAGE_CHARS)


def error_response(status: int, code: str, message: str, details: dict | None = None) -> JSONResponse:
    return JSONResponse({"code": code, "message": message, "details": details or {}}, status_code=status)


def sse(event: str, data: str) -> bytes:
    # Multi-line data is split into several `data:` lines (client re-joins with "\n").
    lines = "".join(f"data: {line}\n" for line in data.split("\n"))
    return f"event: {event}\n{lines}\n".encode()


def create_app(settings_factory: Callable[[], Settings] = Settings.from_env) -> FastAPI:
    @contextlib.asynccontextmanager
    async def lifespan(app: FastAPI):
        setup_logging()
        settings = settings_factory()
        # Fails fast (clear message) if docs are missing or over 12 KB.
        system_prompt = build_system_prompt(settings.docs_dir)
        if not settings.provider_chain():
            log.warning(
                "No API key for the configured provider(s); /api/chat will always answer the fallback text",
                extra={"provider": settings.llm_provider, "fallback": settings.llm_fallback_provider})
        async with httpx.AsyncClient() as http:
            app.state.settings = settings
            app.state.agent = Agent(settings, system_prompt, http)
            app.state.sessions = SessionStore(settings.session_ttl_s)
            app.state.limiter = SessionLimiter(settings.session_msgs_per_hour)
            log.info("assistant-agent started",
                     extra={"provider": settings.llm_provider, "fallback": settings.llm_fallback_provider or None})
            yield

    app = FastAPI(title="assistant-agent", lifespan=lifespan)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError):
        return error_response(400, "VALIDATION_ERROR",
                              f"Solicitud inválida: el mensaje es obligatorio y admite hasta {MAX_MESSAGE_CHARS} caracteres.")

    @app.get("/healthz")
    async def healthz():
        return {"status": "ok"}

    @app.post("/api/chat")
    async def chat(request: Request):
        buyer_id = (request.headers.get("X-Buyer-Id") or "").strip()
        session_id = (request.headers.get("X-Session-Id") or "").strip()
        if not buyer_id:
            return error_response(401, "NO_SESSION", "Falta la sesión de comprador.")
        if not session_id:
            return error_response(400, "VALIDATION_ERROR", "Falta la cabecera X-Session-Id.")
        try:
            body = ChatRequest.model_validate(await request.json())
        except Exception:  # noqa: BLE001 - bad JSON or invalid model
            return error_response(400, "VALIDATION_ERROR",
                                  f"Solicitud inválida: el mensaje es obligatorio y admite hasta {MAX_MESSAGE_CHARS} caracteres.")
        if not app.state.limiter.allow(session_id):
            return error_response(429, "RATE_LIMITED",
                                  "Has alcanzado el máximo de mensajes por hora. Inténtalo más tarde.")
        session = app.state.sessions.register(session_id, buyer_id)
        agent: Agent = app.state.agent

        async def stream():
            async for event, data in agent.events(session, body.message):
                yield sse(event, data)

        return StreamingResponse(stream(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    return app


app = create_app()
