"""Tools (read, reserve/cancel, UI navigation and optional admin), defined once in JSON Schema and translated per provider (spec 9.4).

The buyerId NEVER comes from the model: ToolRunner is built per request with the
buyerId registered for the session, and tool input schemas have no buyer field
(any extra argument the model invents is ignored).
"""
from __future__ import annotations

import json
import logging
from typing import Any

import uuid
from urllib.parse import quote

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
    {
        "name": "get_concert",
        "description": "Datos del evento: nombre, recinto, fecha, estado de la venta y precio por sección. Solo lectura.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "get_seatmap",
        "description": (
            "Mapa de asientos. Sin argumento: disponibles/retenidos/vendidos por sección. Con section_id: además la "
            "lista de asientos DISPONIBLES de esa sección con su seatId. Solo lectura."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"section_id": {"type": "string", "description": "p. ej. PALCO-4 (opcional)."}},
            "additionalProperties": False,
        },
    },
    {
        "name": "get_leaderboard",
        "description": "Ranking de compradores más rápidos. Solo lectura.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "get_my_orders",
        "description": "Últimas órdenes del comprador actual (estado, sección, asientos, total). Solo lectura.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "get_order",
        "description": "Detalle de una orden del comprador actual. Solo lectura.",
        "input_schema": {
            "type": "object",
            "properties": {"order_id": {"type": "string"}},
            "required": ["order_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "reserve_seats",
        "description": (
            "RESERVA (retiene) de 1 a 4 asientos de UNA sección para el comprador actual. No paga: después la app lleva "
            "al usuario a /reservar para que pulse Pagar. Usa seatId reales (p. ej. PALCO-4:A01)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "section_id": {"type": "string", "description": "p. ej. PALCO-4"},
                "seat_ids": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 4},
            },
            "required": ["section_id", "seat_ids"],
            "additionalProperties": False,
        },
    },
    {
        "name": "cancel_order",
        "description": "Cancela una orden del comprador actual que aún no se ha pagado y libera los asientos.",
        "input_schema": {
            "type": "object",
            "properties": {"order_id": {"type": "string"}},
            "required": ["order_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "navigate_to",
        "description": (
            "Lleva al usuario a una pantalla de la app: 'map' (mapa), 'section' (detalle de una sección; requiere "
            "section_id) o 'reserve' (reserva/pago de su orden activa)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "page": {"type": "string", "enum": ["map", "section", "reserve"]},
                "section_id": {"type": "string"},
            },
            "required": ["page"],
            "additionalProperties": False,
        },
    },
]

ADMIN_TOOLS: list[dict[str, Any]] = [
    {"name": "open_sale", "description": "ORGANIZADOR: abre la venta.",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "close_sale", "description": "ORGANIZADOR: cierra la venta.",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "reset_demo", "description": "ORGANIZADOR: reinicia la demo (borra órdenes y libera todos los asientos).",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "get_payment_mode", "description": "ORGANIZADOR: modo actual de la pasarela de pago simulada.",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {
        "name": "set_payment_mode",
        "description": "ORGANIZADOR: cambia la pasarela de pago simulada (OK, FLAKY, SLOW o PAUSED).",
        "input_schema": {
            "type": "object",
            "properties": {
                "mode": {"type": "string", "enum": ["OK", "FLAKY", "SLOW", "PAUSED"]},
                "failure_rate": {"type": "number", "minimum": 0, "maximum": 1},
                "latency_ms": {"type": "integer", "minimum": 0, "maximum": 30000},
            },
            "required": ["mode"],
            "additionalProperties": False,
        },
    },
    {
        "name": "start_bots",
        "description": "ORGANIZADOR: lanza bots de carga que compran entradas.",
        "input_schema": {
            "type": "object",
            "properties": {
                "count": {"type": "integer", "minimum": 1, "maximum": 1000},
                "profile": {"type": "string", "enum": ["burst", "steady", "greedy"]},
                "pay_probability": {"type": "number", "minimum": 0, "maximum": 1},
            },
            "additionalProperties": False,
        },
    },
    {"name": "stop_bots", "description": "ORGANIZADOR: detiene los bots.",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "get_bots_status", "description": "ORGANIZADOR: estado de los bots.",
     "input_schema": {"type": "object", "properties": {}, "additionalProperties": False}},
]


def _all(admin: bool) -> list[dict[str, Any]]:
    return TOOLS + (ADMIN_TOOLS if admin else [])


def anthropic_tools(admin: bool = False) -> list[dict[str, Any]]:
    return [dict(t) for t in _all(admin)]


def openai_tools(admin: bool = False) -> list[dict[str, Any]]:
    return [
        {"type": "function",
         "function": {"name": t["name"], "description": t["description"], "parameters": t["input_schema"]}}
        for t in _all(admin)
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
                 timeout_s: float = 5.0, session_id: str = "", *, nickname: str = "", order_url: str = "",
                 catalog_url: str = "", payment_url: str = "", bots_url: str = "", admin_token: str = "",
                 admin_tools: bool = False):
        self.actions: list[dict] = []  # UI actions (navigate) produced by tools; the agent forwards them as SSE
        self._nickname = nickname or buyer_id
        self._order_url = order_url.rstrip("/")
        self._catalog_url = catalog_url.rstrip("/")
        self._payment_url = payment_url.rstrip("/")
        self._bots_url = bots_url.rstrip("/")
        self._admin_token = admin_token
        self._admin = admin_tools
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
            if name == "get_concert":
                return _dump(await self._concert_info())
            if name == "get_seatmap":
                return _dump(await self._seatmap(args.get("section_id")))
            if name == "get_leaderboard":
                return _dump(await self._get("/api/leaderboard", {"concertId": self._concert, "limit": 5}))
            if name == "get_my_orders":
                return _dump(await self._get(f"/api/buyers/{self._buyer_id}/orders", {"limit": 5}))
            if name == "get_order":
                return _dump(await self._send("GET", f"{self._order_url}/api/orders/{_seg(args.get('order_id'))}",
                                              headers=self._buyer_headers()))
            if name == "reserve_seats":
                return _dump(await self._reserve(args))
            if name == "cancel_order":
                return _dump(await self._send("POST", f"{self._order_url}/api/orders/{_seg(args.get('order_id'))}/cancel",
                                              headers=self._buyer_headers(), body={}))
            if name == "navigate_to":
                return _dump(self._navigate(args))
            if name in ADMIN_NAMES:
                if not self._admin:
                    return _dump({"error": f"Herramienta no disponible: {name}"})
                return _dump(await self._admin_call(name, args))
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


    # ---- buyer actions -----------------------------------------------------------------------------------------

    def _buyer_headers(self) -> dict[str, str]:
        return {"X-Buyer-Id": self._buyer_id, "X-Buyer-Nickname": quote(self._nickname)}

    async def _send(self, method: str, url: str, *, headers: dict | None = None, body: Any = None,
                    params: dict | None = None) -> Any:
        """HTTP call that returns the JSON body; 4xx become {"error": ...} for the model instead of an exception."""
        r = await self._http.request(method, url, headers=headers, json=body, params=params, timeout=self._timeout)
        if r.status_code >= 500:
            r.raise_for_status()
        try:
            data = r.json()
        except ValueError:
            data = {"raw": r.text[:200]}
        if r.status_code >= 400:
            return {"error": data, "status": r.status_code}
        return data

    async def _concert_info(self) -> Any:
        c = await self._send("GET", f"{self._catalog_url}/api/concert")
        return c

    async def _seatmap(self, section_id: Any) -> Any:
        sm = await self._get("/api/seatmap", {"concertId": self._concert})
        want = str(section_id).strip().upper() if section_id else ""
        out = []
        for sec in sm.get("sections", []):
            seats = sec.get("seats", [])
            counts = {st: sum(1 for x in seats if x.get("state") == st) for st in ("AVAILABLE", "HELD", "SOLD")}
            row = {"sectionId": sec["sectionId"], "label": sec.get("label"), "priceCents": sec.get("priceCents"),
                   "available": counts["AVAILABLE"], "held": counts["HELD"], "sold": counts["SOLD"]}
            if want and sec["sectionId"] == want:
                row["availableSeats"] = [x["seatId"] for x in seats if x.get("state") == "AVAILABLE"]
            out.append(row)
        if want and not any(r["sectionId"] == want for r in out):
            return {"error": f"Sección desconocida: {want}", "sections": [r["sectionId"] for r in out]}
        return {"sections": out}

    async def _reserve(self, args: dict) -> Any:
        section = str(args.get("section_id", "")).strip().upper()
        seats = args.get("seat_ids")
        if not section or not isinstance(seats, list) or not 1 <= len(seats) <= 4:
            return {"error": "Indica section_id y de 1 a 4 seat_ids."}
        res = await self._send(
            "POST", f"{self._order_url}/api/orders",
            headers={**self._buyer_headers(), "Idempotency-Key": f"assistant-{uuid.uuid4()}"},
            body={"concertId": self._concert, "sectionId": section, "seatIds": [str(x) for x in seats]})
        if isinstance(res, dict) and "orderId" in res:
            self.actions.append({"type": "navigate", "path": "/reservar", "orderId": res["orderId"]})
            return {**res, "next": "El usuario fue llevado a /reservar: solo debe pulsar Pagar antes de que venza la retención."}
        return res

    def _navigate(self, args: dict) -> Any:
        page = str(args.get("page", "")).lower()
        if page == "map":
            self.actions.append({"type": "navigate", "path": "/mapa"})
        elif page == "section":
            sid = str(args.get("section_id", "")).strip().upper()
            kind, _, ident = sid.partition("-")
            if not kind or not ident:
                return {"error": "Indica section_id, p. ej. PALCO-4."}
            self.actions.append({"type": "navigate", "path": f"/mapa/{kind.lower()}/{ident.lower()}"})
        elif page == "reserve":
            self.actions.append({"type": "navigate", "path": "/reservar"})
        else:
            return {"error": "page debe ser map, section o reserve."}
        return {"ok": True, "navigating": self.actions[-1]["path"]}

    # ---- organizer (opt-in) -------------------------------------------------------------------------------------

    async def _admin_call(self, name: str, args: dict) -> Any:
        h = {"X-Admin-Token": self._admin_token}
        if name in ("open_sale", "close_sale"):
            return await self._send("POST", f"{self._catalog_url}/admin/sale/{name.split('_')[0]}", headers=h, body={})
        if name == "reset_demo":
            return await self._send("POST", f"{self._catalog_url}/admin/reset", headers=h, body={})
        if name == "get_payment_mode":
            return await self._send("GET", f"{self._payment_url}/admin/mode", headers=h)
        if name == "set_payment_mode":
            body = {"mode": str(args.get("mode", "OK")).upper()}
            if "failure_rate" in args:
                body["failureRate"] = args["failure_rate"]
            if "latency_ms" in args:
                body["latencyMs"] = args["latency_ms"]
            return await self._send("PUT", f"{self._payment_url}/admin/mode", headers=h, body=body)
        if name == "start_bots":
            profile = str(args.get("profile") or "burst")
            body = {"count": int(args.get("count") or 50), "profile": profile,
                    "rampSeconds": 30 if profile == "steady" else 2,
                    "payProbability": float(args.get("pay_probability", 0.85))}
            return await self._send("POST", f"{self._bots_url}/bots/start", headers=h, body=body)
        if name == "stop_bots":
            return await self._send("POST", f"{self._bots_url}/bots/stop", headers=h, body={})
        return await self._send("GET", f"{self._bots_url}/bots/status", headers=h)


ADMIN_NAMES = {t["name"] for t in ADMIN_TOOLS}


def _seg(value: Any) -> str:
    return quote(str(value or ""), safe="")
