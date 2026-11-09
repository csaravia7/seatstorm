"""Read-only tools, defined once in JSON Schema and translated per provider (spec 9.4).

The buyerId NEVER comes from the model: ToolRunner is built per request with the
buyerId registered for the session, and tool input schemas have no buyer field
(any extra argument the model invents is ignored).
"""
from __future__ import annotations

import json
import logging
from typing import Any

import httpx

log = logging.getLogger("assistant.tools")

MAX_TOOL_OUTPUT_CHARS = 4000

TOOLS: list[dict[str, Any]] = [
    {
        "name": "get_order_timeline",
        "description": (
            "Línea de tiempo de una orden del comprador actual. Sin argumento devuelve la de su última orden. "
            "Solo lectura."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"order_id": {"type": "string", "description": "Id de la orden (opcional)."}},
            "additionalProperties": False,
        },
    },
    {
        "name": "find_seats",
        "description": "Busca grupos de asientos disponibles (máx. 5 grupos). Solo lectura.",
        "input_schema": {
            "type": "object",
            "properties": {
                "section_id": {"type": "string", "description": "Sección, p. ej. PLATEA-A (opcional)."},
                "quantity": {"type": "integer", "minimum": 1, "maximum": 4},
                "adjacent": {"type": "boolean", "description": "true para asientos contiguos en la misma fila."},
            },
            "required": ["quantity", "adjacent"],
            "additionalProperties": False,
        },
    },
    {
        "name": "explain_reason",
        "description": (
            "Explica un código de motivo: SEAT_TAKEN, HOLD_EXPIRED, PAYMENT_FAILED, CARD_DECLINED, "
            "INSUFFICIENT_FUNDS, USER_CANCELLED, SALE_CLOSED."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"code": {"type": "string"}},
            "required": ["code"],
            "additionalProperties": False,
        },
    },
    {
        "name": "get_sale_stats",
        "description": "Asientos disponibles, retenidos y vendidos, y estado de la venta. Solo lectura.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
]


def anthropic_tools() -> list[dict[str, Any]]:
    return [dict(t) for t in TOOLS]


def openai_tools() -> list[dict[str, Any]]:
    return [
        {"type": "function",
         "function": {"name": t["name"], "description": t["description"], "parameters": t["input_schema"]}}
        for t in TOOLS
    ]


REASONS = {
    "SEAT_TAKEN": "Otra persona reservó alguno de esos asientos un instante antes. No se cobró nada; puede elegir otros.",
    "HOLD_EXPIRED": "Se agotó el tiempo de retención de los asientos (60 s, o 120 s extra tras pedir el pago). "
                    "Si el pago ya estaba aprobado, se reembolsa automáticamente.",
    "PAYMENT_FAILED": "El pago no se completó. Los asientos se liberan y puede intentarlo de nuevo con una orden nueva.",
    "CARD_DECLINED": "La tarjeta simulada fue rechazada (causa concreta de PAYMENT_FAILED).",
    "INSUFFICIENT_FUNDS": "La tarjeta simulada no tenía saldo suficiente (causa concreta de PAYMENT_FAILED).",
    "USER_CANCELLED": "La persona canceló la orden antes de pagar; los asientos quedaron libres.",
    "SALE_CLOSED": "La venta está cerrada y no acepta órdenes nuevas hasta que el organizador la abra.",
}


def _dump(obj: Any) -> str:
    text = json.dumps(obj, ensure_ascii=False)
    if len(text) > MAX_TOOL_OUTPUT_CHARS:
        text = text[:MAX_TOOL_OUTPUT_CHARS] + "…(truncado)"
    return text


def _order_id(o: dict) -> str | None:
    return o.get("orderId") or o.get("order_id")


class ToolRunner:
    def __init__(self, http: httpx.AsyncClient, query_url: str, concert_id: str, buyer_id: str,
                 timeout_s: float = 5.0, session_id: str = ""):
        self._http = http
        self._base = query_url.rstrip("/")
        self._concert = concert_id
        self._buyer_id = buyer_id
        self._timeout = timeout_s
        self._session_id = session_id

    async def call(self, name: str, args: dict | None) -> str:
        """Execute a tool; never raises. Returns a JSON string."""
        args = args if isinstance(args, dict) else {}
        try:
            if name == "get_order_timeline":
                return _dump(await self._order_timeline(args.get("order_id")))
            if name == "find_seats":
                return _dump(await self._find_seats(args))
            if name == "explain_reason":
                code = str(args.get("code", "")).strip().upper()
                if code in REASONS:
                    return _dump({"code": code, "explanation": REASONS[code]})
                return _dump({"code": code, "explanation": "Código desconocido.", "known": sorted(REASONS)})
            if name == "get_sale_stats":
                return _dump(await self._get("/api/stats", {"concertId": self._concert}))
            return _dump({"error": f"Herramienta desconocida: {name}"})
        except (httpx.HTTPError, ValueError) as exc:
            log.warning("tool_failed", extra={"tool": name, "error": repr(exc), "sessionId": self._session_id})
            return _dump({"error": "No pude consultar los datos ahora mismo."})

    async def _get(self, path: str, params: dict | None = None) -> Any:
        r = await self._http.get(self._base + path, params=params, timeout=self._timeout)
        r.raise_for_status()
        return r.json()

    async def _order_timeline(self, order_id: Any) -> Any:
        orders = await self._get(f"/api/buyers/{self._buyer_id}/orders", {"limit": 5})
        if not isinstance(orders, list) or not orders:
            return {"message": "no hay órdenes"}
        if order_id:
            chosen = next((o for o in orders if _order_id(o) == str(order_id)), None)
            if chosen is None:
                return {"message": "no encontré esa orden entre las órdenes recientes del comprador"}
        else:
            chosen = orders[0]  # most recent first
        oid = _order_id(chosen)
        timeline = await self._get(f"/api/orders/{oid}/timeline")
        return {"order": chosen, "timeline": timeline}

    async def _find_seats(self, args: dict) -> Any:
        params: dict[str, Any] = {
            "concertId": self._concert,
            "quantity": max(1, min(4, int(args.get("quantity") or 1))),
            "adjacent": "true" if args.get("adjacent") else "false",
        }
        if args.get("section_id"):
            params["sectionId"] = str(args["section_id"])
        groups = await self._get("/api/seats/search", params)
        return groups if groups else {"message": "no hay grupos de asientos disponibles con esos criterios"}
