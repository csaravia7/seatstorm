import { startTransition, useEffect, useRef } from 'react';
import type { FeedItem, LeaderboardRow, MeMessage, SeatDiff } from '../api/types';
import { useMapStore } from '../state/mapStore';
import { syncSeatmap } from './sync';

export interface LiveHandlers {
  onMe?: (msg: MeMessage) => void;
  onReset?: () => void;
}

const parse = <T,>(e: Event): T | null => {
  try {
    return JSON.parse((e as MessageEvent<string>).data) as T;
  } catch {
    return null;
  }
};

/**
 * Opens the BFF SSE stream. Every `hello` (first connect and every reconnect) re-requests /api/seatmap.
 * Seat diffs are applied inside startTransition so rendering never blocks input.
 */
export function useLive(view: 'public' | 'stage', handlers: LiveHandlers = {}): void {
  const h = useRef(handlers);
  h.current = handlers;

  useEffect(() => {
    let es: EventSource | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let closed = false;
    const store = useMapStore.getState;

    const connect = () => {
      es = new EventSource(`/live?view=${view}`);
      es.addEventListener('hello', (e) => {
        const d = parse<{ serverTimeMs: number }>(e);
        store().setConnected(true, d?.serverTimeMs);
        void syncSeatmap();
      });
      es.addEventListener('seat', (e) => {
        const diffs = parse<SeatDiff[]>(e);
        if (diffs) startTransition(() => store().applyDiffs(diffs));
      });
      es.addEventListener('sale', (e) => {
        const d = parse<{ status: 'OPEN' | 'CLOSED' }>(e);
        if (d) store().setSale(d.status, true);
      });
      es.addEventListener('reset', () => {
        store().reset();
        h.current.onReset?.();
        void syncSeatmap();
      });
      es.addEventListener('me', (e) => {
        const m = parse<MeMessage>(e);
        if (m) h.current.onMe?.(m);
      });
      es.addEventListener('feed', (e) => {
        const f = parse<FeedItem>(e);
        if (f) store().pushFeed(f);
      });
      es.addEventListener('leaderboard', (e) => {
        const rows = parse<LeaderboardRow[]>(e);
        if (rows) store().setLeaderboard(rows);
      });
      es.onerror = () => {
        store().setConnected(false);
        // The browser retries by itself unless the server refused the connection (e.g. 503).
        if (es && es.readyState === EventSource.CLOSED && !closed) {
          retry = setTimeout(connect, 3000);
        }
      };
    };
    connect();
    return () => {
      closed = true;
      clearTimeout(retry);
      es?.close();
    };
  }, [view]);
}
