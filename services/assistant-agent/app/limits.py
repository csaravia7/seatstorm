"""Global token bucket and per-session hourly limit (spec 9.6)."""
from __future__ import annotations

import asyncio
import time
from collections import defaultdict, deque
from typing import Callable


class LimiterTimeout(Exception):
    """No turn obtained from the global limiter within the allowed wait."""


class TokenBucket:
    """Reservation-based token bucket: capacity == rpm, refill rpm/60 per second.

    A caller reserves a token (the balance may go negative) and sleeps until it
    is due; if the due time exceeds `timeout` the reservation is rolled back and
    LimiterTimeout is raised. Provider calls therefore never exceed the rate.
    """

    def __init__(self, rpm: int, clock: Callable[[], float] = time.monotonic):
        self.capacity = float(max(rpm, 1))
        self.rate = self.capacity / 60.0
        self._clock = clock
        self._tokens = self.capacity
        self._last = clock()

    def _refill(self) -> None:
        now = self._clock()
        self._tokens = min(self.capacity, self._tokens + (now - self._last) * self.rate)
        self._last = now

    async def acquire(self, timeout: float) -> None:
        # No awaits between refill and reservation: atomic on the event loop.
        self._refill()
        self._tokens -= 1.0
        if self._tokens >= 0:
            return
        wait = -self._tokens / self.rate
        if wait > timeout:
            self._tokens += 1.0
            raise LimiterTimeout(f"no slot within {timeout}s")
        await asyncio.sleep(wait)


class SessionLimiter:
    """Max N messages per session in a sliding hour."""

    def __init__(self, max_per_hour: int = 20, window_s: float = 3600.0,
                 clock: Callable[[], float] = time.monotonic):
        self.max = max_per_hour
        self.window = window_s
        self._clock = clock
        self._hits: dict[str, deque] = defaultdict(deque)

    def allow(self, session_id: str) -> bool:
        now = self._clock()
        q = self._hits[session_id]
        while q and now - q[0] >= self.window:
            q.popleft()
        if len(q) >= self.max:
            return False
        q.append(now)
        return True

    def purge(self) -> None:
        now = self._clock()
        for sid in [s for s, q in self._hits.items() if not q or now - q[-1] >= self.window]:
            del self._hits[sid]
