"""Deterministic guardrails (no extra deps, no extra LLM calls, zero added latency).

Input  (before the model is called): prompt_injection, pii_leakage, toxicity, off_topic.
Output (while streaming):            pii_leakage (redact), prompt_leak, toxicity, hallucination (grounding, flag only).

Check names match the evaluation names shown in Arize/Dynatrace (Toxicity, PII Leakage, Hallucination, Relevance...) so
`guardrail_triggered` log lines can be correlated with the evaluators later. Semantic evaluations (Relevance, Fluency,
Answer Completeness, Bias, Conciseness, Context Relevance, User Frustration) need an LLM judge and belong to the
observability platform, not to the request path.

Modes (GUARDRAILS_MODE): enforce = block/redact; monitor = only log (to compare in a demo); off = disabled.
"""
from __future__ import annotations

import logging
import re
import unicodedata
from dataclasses import dataclass

log = logging.getLogger("assistant.guardrails")

MODES = ("enforce", "monitor", "off")

REFUSALS = {
    "prompt_injection": "No puedo seguir esa instrucción. Puedo ayudarte con la venta de entradas: asientos, tu orden o cómo funciona SeatStorm.",
    "pii_leakage": "Por tu seguridad, no compartas datos personales ni de pago (tarjetas, correos, teléfonos, documentos o claves) en el chat. Reformula la pregunta sin esos datos.",
    "toxicity": "Prefiero mantener la conversación respetuosa. Si quieres, te ayudo con tus asientos, tu orden o dudas de la venta.",
    "off_topic": "Solo puedo ayudarte con la compra de entradas de SeatStorm: disponibilidad de asientos, el estado de tu orden, motivos de un error o cómo funciona la venta.",
}
OUTPUT_BLOCKED = "Prefiero no responder eso. ¿Te ayudo con tus asientos o el estado de tu orden?"


@dataclass(frozen=True)
class Verdict:
    guardrail: str
    direction: str  # "input" | "output"
    detail: str = ""


def _norm(text: str) -> str:
    """Lowercase, strip accents and zero-width/format characters (cheap de-obfuscation)."""
    text = "".join(c for c in text if unicodedata.category(c) != "Cf")
    text = unicodedata.normalize("NFKD", text)
    return "".join(c for c in text if not unicodedata.combining(c)).lower()


# ---- prompt injection -------------------------------------------------------------------------

_INJECTION = [re.compile(p) for p in (
    r"ignor\w*\s+(todas?\s+)?(las\s+|tus\s+|mis\s+)?(instrucciones|reglas|indicaciones|restricciones)",
    r"olvid\w*\s+(todas?\s+)?(las\s+|tus\s+)?(instrucciones|reglas|restricciones)",
    r"(ignore|disregard|forget)\s+(all\s+|any\s+)?(the\s+|your\s+|previous\s+|prior\s+|above\s+)+(instructions|rules|prompts?)",
    r"(system|developer|sistema)\s*(prompt|message|mensaje)",
    r"(revela|muestra|imprime|repite|dime|reveal|show|print|repeat|leak)\w*\s+(tu\s+|el\s+|your\s+|the\s+)?(prompt|instrucciones\s+(iniciales|internas|del\s+sistema)|initial\s+instructions)",
    r"(developer|dev|god|jailbreak|dan|modo\s+desarrollador|sudo)\s*mode",
    r"\bjailbreak\b|\bdan\b.{0,20}(mode|modo)|do\s+anything\s+now",
    r"(a\s+partir\s+de\s+ahora|desde\s+ahora)\s+(eres|actua|actuaras|vas\s+a\s+ser)",
    r"you\s+are\s+now\b|act\s+as\s+(if\s+you\s+(are|were)|an?\s+)|pretend\s+(to\s+be|you)",
    r"(finge|simula|actua\s+como|haz\s+de)\s+(que\s+eres|ser|un|una|el|la)\b",
    r"(sin|without)\s+(restricciones|filtros|limites|restrictions|filters|rules)",
    r"<\|.{0,20}\|>|\[/?inst\]|###\s*(system|instruction)|<\s*/?\s*(system|assistant)\s*>",
    r"(buyer[_ ]?id|x-buyer-id|session[_ ]?id)\s*[:=]",
    r"(ordenes|pedidos|orders)\s+de\s+(otro|otra|otros|otras|another|other)\s+(usuario|persona|comprador|user|buyer)",
)]


def check_prompt_injection(text: str) -> str | None:
    n = _norm(text)
    for rx in _INJECTION:
        if rx.search(n):
            return rx.pattern[:60]
    return None


# ---- PII / secrets ----------------------------------------------------------------------------

_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
_CARD = re.compile(r"(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)")
_PHONE_INTL = re.compile(r"(?<![\w+])\+\d{1,3}[ .-]?\(?\d{1,4}\)?(?:[ .-]?\d{2,4}){2,4}(?!\d)")
_PHONE_LOCAL = re.compile(r"(?<![\w.])(?:\d[ .-]?){8,11}\d(?!\d)")
_DOC_ID = re.compile(r"\b\d{7,8}[- ]?[A-Za-z]\b|\b[A-Za-z]\d{7,8}[- ]?[A-Za-z]?\b|\b\d{3}-\d{2}-\d{4}\b")
_IBAN = re.compile(r"\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{4}){3,7}(?:[ ]?[A-Z0-9]{1,4})?\b")
_SECRET = re.compile(r"\b(?:sk-[A-Za-z0-9_-]{16,}|nvapi-[A-Za-z0-9_-]{16,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|"
                     r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})\b|Bearer\s+[A-Za-z0-9._-]{16,}")


def _luhn(digits: str) -> bool:
    total, alt = 0, False
    for ch in reversed(digits):
        d = int(ch)
        if alt:
            d = d * 2 - 9 if d * 2 > 9 else d * 2
        total += d
        alt = not alt
    return total % 10 == 0


def _cards(text: str):
    for m in _CARD.finditer(text):
        digits = re.sub(r"\D", "", m.group())
        if 13 <= len(digits) <= 19 and _luhn(digits):
            yield m


def check_pii_input(text: str) -> str | None:
    """Sensitive data the user should not type into a chat. Returns the PII kind or None."""
    if _SECRET.search(text):
        return "secret"
    if _EMAIL.search(text):
        return "email"
    if next(_cards(text), None):
        return "card"
    if _IBAN.search(text.replace(" ", " ")):
        return "iban"
    if _DOC_ID.search(text):
        return "document_id"
    if _PHONE_INTL.search(text) or _PHONE_LOCAL.search(text):
        return "phone"
    return None


def redact_pii_output(text: str) -> tuple[str, list[str]]:
    """Conservative redaction for model output (long digit runs such as epoch millis are left alone)."""
    kinds: list[str] = []

    def sub(rx: re.Pattern, label: str, s: str) -> str:
        new, n = rx.subn(f"[{label} oculto]", s)
        if n:
            kinds.append(label)
        return new

    text = sub(_SECRET, "clave", text)
    text = sub(_EMAIL, "correo", text)
    out, last, hit = [], 0, False
    for m in _cards(text):
        out += [text[last:m.start()], "[tarjeta oculta]"]
        last, hit = m.end(), True
    if hit:
        kinds.append("tarjeta")
        text = "".join(out) + text[last:]
    text = sub(_PHONE_INTL, "teléfono", text)
    text = sub(_IBAN, "cuenta", text)
    return text, kinds


# ---- toxicity ---------------------------------------------------------------------------------

_TOXIC = [re.compile(p) for p in (
    r"\b(idiota|imbecil|estupid[oa]|inutil|basura|asqueros[oa]|malnacid[oa]|hijo\s+de\s+puta|hijueputa|puta|puto|"
    r"mierda|cabron|cabrona|pendej[oa]|gilipollas|co[nñ]o|carajo|maldit[oa]|mam[oó]n|zorra|marica|subnormal|retrasad[oa])\b",
    r"\b(fuck\w*|shit\w*|bitch\w*|asshole|bastard|idiot|moron|stupid|dumbass|cunt|retard\w*|piece\s+of\s+(shit|crap))\b",
    r"\b(te\s+voy\s+a\s+(matar|golpear|encontrar)|voy\s+a\s+(matarte|hackearte)|i('| a)?m\s+going\s+to\s+(kill|hurt)|"
    r"i\s+will\s+(kill|hurt)\s+you|muerete|kill\s+yourself)\b",
)]


def check_toxicity(text: str) -> str | None:
    n = _norm(text)
    for rx in _TOXIC:
        m = rx.search(n)
        if m:
            return m.group(0)[:20]
    return None


# ---- off topic --------------------------------------------------------------------------------

_DOMAIN = re.compile(
    r"asiento|butaca|fila|secci[oó]n|platea|palco|entrada|boleto|ticket|concierto|neon|show|evento|venta|vend|"
    r"orden|pedido|compra|compr|reserv|retenc|retener|hold|pago|pagar|tarjeta|reembolso|cancel|expir|"
    r"seat|order|payment|refund|bot|swarm|kafka|arquitectura|saga|outbox|microservicio|servicio|cat[aá]logo|"
    r"notificaci|stats|estad[ií]stica|disponib|agotad|libre|ocupad|precio|cuesta|costo|cu[aá]nt|"
    r"seatstorm|dynatrace|observabilidad|trazas?|tiempo|l[ií]nea|motivo|error|c[oó]digo|fall[oó]|"
    r"SEAT_TAKEN|HOLD_EXPIRED|PAYMENT_FAILED|CARD_DECLINED|INSUFFICIENT_FUNDS|USER_CANCELLED|SALE_CLOSED|"
    r"c[oó]mo\s+funciona|ayuda|ayudar|puedes\s+hacer|qu[eé]\s+(haces|eres|puedes)|hola|buen[oa]s|gracias|"
    r"hello|hi\b|thanks|help|what\s+can\s+you", re.IGNORECASE)

_OFF_TOPIC = [re.compile(p) for p in (
    r"\b(escrib\w*|gener\w*|cre[ae]\w*|haz\w*|dame|programa\w*|implementa\w*|write|generate|create|give\s+me)\b.{0,40}"
    r"\b(codigo|script|funcion|programa|clase|poema|cancion|cuento|historia|ensayo|carta|correo|email|chiste|"
    r"code|function|poem|song|story|essay|joke|sql|regex)\b",
    r"\b(receta|cocinar|ingredientes|recipe)\b",
    r"\b(clima|tiempo\s+(hara|que\s+hace)\s+(hoy|manana)|pronostico|weather|forecast)\b",
    r"\b(presidente|elecciones|politic[ao]s?|partido|religion|dios|aborto|guerra|election|president|politics)\b",
    r"\b(bitcoin|criptomoneda|acciones\s+de|bolsa\s+de\s+valores|inversion|invertir|crypto|stock\s+market)\b",
    r"\b(diagnostico|sintomas|medicina|medicamento|dosis|enfermedad|symptom|diagnos\w+|medication)\b",
    r"\b(abogado|demanda\s+judicial|legal\s+advice|consejo\s+legal)\b",
    r"\b(traduce|traducir|translate|resume\s+este|summarize\s+this)\b",
    r"\b(tarea|homework|examen|resuelve|ecuacion|derivada|integral|matematicas|solve\s+this)\b",
    r"\b(capital\s+de|quien\s+(gano|invento|escribio)|historia\s+de|who\s+(won|invented|wrote))\b",
    r"\b(hackear|hackea|exploit|malware|phishing|ddos|inyeccion\s+sql|sql\s+injection)\b",
)]


def check_off_topic(text: str) -> str | None:
    """Clear off-topic requests only (a message with domain vocabulary always passes)."""
    n = _norm(text)
    if _DOMAIN.search(n):
        return None
    for rx in _OFF_TOPIC:
        if rx.search(n):
            return rx.pattern[:40]
    return None


# ---- input pipeline ---------------------------------------------------------------------------

def check_input(text: str) -> Verdict | None:
    """First failing input guardrail, in priority order (cheapest, most dangerous first)."""
    if (d := check_prompt_injection(text)):
        return Verdict("prompt_injection", "input", d)
    if (d := check_pii_input(text)):
        return Verdict("pii_leakage", "input", d)
    if (d := check_toxicity(text)):
        return Verdict("toxicity", "input", d)
    if (d := check_off_topic(text)):
        return Verdict("off_topic", "input", d)
    return None


# ---- output pipeline --------------------------------------------------------------------------

_SENTENCE_END = re.compile(r"[.!?…:;\n]\s*$|[.!?…]\s")


def _leak_fragments(system_prompt: str) -> list[str]:
    """Distinctive 8-word windows of the base prompt: if the reply repeats one, it leaked the prompt."""
    words = _norm(system_prompt.split("# Documentos")[0]).split()
    return [" ".join(words[i:i + 8]) for i in range(0, max(0, len(words) - 7), 4)]


_ID = re.compile(r"\b(?:[A-Z]{3,}[-_][A-Z0-9]+(?:[-_][A-Z0-9]+)*|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|"
                 r"[a-z]{1,4}_[0-9a-f]{6,}|\d{6,})\b")


class OutputGuard:
    """Per-response guard. `feed()` buffers tokens until a sentence boundary, redacts PII, and blocks on toxicity or
    prompt leakage (after a block nothing else is emitted). `finish()` flushes and runs the grounding check."""

    def __init__(self, system_prompt: str, enforce: bool, sid: str = ""):
        self.enforce = enforce
        self.sid = sid
        self._leaks = _leak_fragments(system_prompt)
        self._known = set(_ID.findall(system_prompt))
        self._buf = ""
        self.raw: list[str] = []      # what the model produced
        self.emitted: list[str] = []  # what the user saw
        self.blocked: Verdict | None = None
        self.verdicts: list[Verdict] = []
        self._tail = ""  # last chars already inspected, to catch matches that span sentences

    def _flag(self, v: Verdict) -> None:
        self.verdicts.append(v)
        log.warning("guardrail_triggered", extra={
            "sessionId": self.sid, "guardrail": v.guardrail, "direction": v.direction,
            "action": "block" if self.enforce and v.guardrail in ("toxicity", "prompt_leak") else
                      ("redact" if self.enforce and v.guardrail == "pii_leakage" else "flag"),
            "mode": "enforce" if self.enforce else "monitor", "detail": v.detail})

    def _process(self, chunk: str) -> list[str]:
        """Inspect one complete chunk; returns the pieces to emit."""
        self.raw.append(chunk)
        window = _norm(self._tail + chunk)
        self._tail = chunk[-120:]
        if (d := check_toxicity(chunk)):
            self._flag(Verdict("toxicity", "output", d))
            if self.enforce:
                self.blocked = self.blocked or Verdict("toxicity", "output", d)
                return [OUTPUT_BLOCKED] if not self.emitted else []
        if any(f in window for f in self._leaks):
            self._flag(Verdict("prompt_leak", "output", "system prompt fragment"))
            if self.enforce:
                self.blocked = self.blocked or Verdict("prompt_leak", "output", "system prompt fragment")
                return [OUTPUT_BLOCKED] if not self.emitted else []
        clean, kinds = redact_pii_output(chunk)
        for k in kinds:
            self._flag(Verdict("pii_leakage", "output", k))
        return [clean if self.enforce else chunk]

    def feed(self, token: str) -> list[str]:
        if self.blocked:
            self.raw.append(token)
            return []
        self._buf += token
        if not _SENTENCE_END.search(self._buf) and len(self._buf) < 240:
            return []
        chunk, self._buf = self._buf, ""
        out = self._process(chunk)
        self.emitted += out
        return out

    def finish(self, grounding_text: str = "") -> list[str]:
        out: list[str] = []
        if self._buf and not self.blocked:
            out = self._process(self._buf)
            self.emitted += out
        self._buf = ""
        self._grounding(grounding_text)
        return out

    def _grounding(self, grounding_text: str) -> None:
        """Hallucination heuristic: identifiers/long numbers in the reply that appear in no tool output, user message
        or reference doc. Flag only (a reply cannot be retracted once streamed)."""
        known = self._known | set(_ID.findall(grounding_text))
        answer = "".join(self.raw)
        unknown = sorted({i for i in _ID.findall(answer) if i not in known})
        if unknown:
            self._flag(Verdict("hallucination", "output", ",".join(unknown[:5])))

    @property
    def text(self) -> str:
        """What the user saw (stored in the session history)."""
        return "".join(self.emitted)
