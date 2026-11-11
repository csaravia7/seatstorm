"""In-memory sessions: sessionId -> buyerId and conversation memory (TTL 1 h)."""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Callable

MAX_TURNS = 10


@dataclass
class Session:
    session_id: str
    buyer_id: str
    nickname: str = ""
    history: list = field(default_factory=list)  # provider-neutral [{"role", "content": str}]
    last_seen: float = 0.0

    def add_turn(self, user_text: str, assistant_text: str) -> None:
        self.history.append({"role": "user", "content": user_text})
        self.history.append({"role": "assistant", "content": assistant_text or "(sin respuesta)"})
        del self.history[: max(0, len(self.history) - 2 * MAX_TURNS)]


class SessionStore:
    def __init__(self, ttl_s: float = 3600.0, clock: Callable[[], float] = time.monotonic):
        self.ttl = ttl_s
        self._clock = clock
        self._sessions: dict[str, Session] = {}

    def register(self, session_id: str, buyer_id: str, nickname: str = "") -> Session:
        """Bind sessionId to buyerId (done by the endpoint before calling the model)."""
        now = self._clock()
        self._purge(now)
        s = self._sessions.get(session_id)
        if s is None or s.buyer_id != buyer_id:
            s = Session(session_id, buyer_id, nickname)
            self._sessions[session_id] = s
        s.last_seen = now
        return s

    def _purge(self, now: float) -> None:
        for sid in [k for k, v in self._sessions.items() if now - v.last_seen > self.ttl]:
            del self._sessions[sid]

    def __len__(self) -> int:
        return len(self._sessions)
