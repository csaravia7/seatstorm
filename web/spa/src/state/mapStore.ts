import { create } from 'zustand';
import type { FeedItem, LeaderboardRow, SeatDiff, SeatmapDto, SeatState } from '../api/types';

export interface SeatEntry {
  state: SeatState;
  version: number;
}

export type SaleStatus = 'OPEN' | 'CLOSED' | null;

export interface MapState {
  seats: Map<string, SeatEntry>;
  priceBySection: Record<string, number>;
  saleStatus: SaleStatus;
  /** Epoch ms until which the "sale opens" countdown is shown (live transitions only). */
  openingUntil: number | null;
  connected: boolean;
  /** serverTime - clientTime, from the SSE hello event. */
  clockOffsetMs: number;
  feed: FeedItem[];
  leaderboard: LeaderboardRow[];
  loading: boolean;
  buffer: SeatDiff[];

  applyDiffs(diffs: SeatDiff[]): void;
  beginLoad(): void;
  loadSeatmap(map: SeatmapDto): void;
  setSale(status: 'OPEN' | 'CLOSED', live?: boolean): void;
  setConnected(c: boolean, serverTimeMs?: number): void;
  pushFeed(item: FeedItem): void;
  setLeaderboard(rows: LeaderboardRow[]): void;
  /** Demo reset: forget every seat version so the fresh seatmap wins. */
  reset(): void;
}

export const FEED_LIMIT = 12;
export const OPENING_MS = 3000;

/** Applies a diff only when its version is greater than the one we already have. */
function applyInto(seats: Map<string, SeatEntry>, diffs: SeatDiff[]): Map<string, SeatEntry> | null {
  let next: Map<string, SeatEntry> | null = null;
  for (const d of diffs) {
    const cur = (next ?? seats).get(d.seatId);
    if (cur && d.version <= cur.version) continue;
    if (!next) next = new Map(seats);
    next.set(d.seatId, { state: d.state, version: d.version });
  }
  return next;
}

export const useMapStore = create<MapState>((set, get) => ({
  seats: new Map(),
  priceBySection: {},
  saleStatus: null,
  openingUntil: null,
  connected: false,
  clockOffsetMs: 0,
  feed: [],
  leaderboard: [],
  loading: false,
  buffer: [],

  applyDiffs(diffs) {
    const { seats, loading, buffer } = get();
    const next = applyInto(seats, diffs);
    if (loading) set({ buffer: [...buffer, ...diffs] });
    if (next) set({ seats: next });
  },

  beginLoad() {
    set({ loading: true, buffer: [] });
  },

  loadSeatmap(map) {
    const seats = new Map<string, SeatEntry>();
    const prices: Record<string, number> = {};
    for (const sec of map.sections) {
      prices[sec.sectionId] = sec.priceCents;
      for (const s of sec.seats) seats.set(s.seatId, { state: s.state, version: s.version });
    }
    // Diffs that arrived while the request was in flight may be newer than the snapshot.
    const merged = applyInto(seats, get().buffer) ?? seats;
    set({ seats: merged, priceBySection: prices, loading: false, buffer: [] });
  },

  setSale(status, live = false) {
    const prev = get().saleStatus;
    set({
      saleStatus: status,
      openingUntil: live && prev === 'CLOSED' && status === 'OPEN' ? Date.now() + OPENING_MS : status === 'CLOSED' ? null : get().openingUntil,
    });
  },

  setConnected(connected, serverTimeMs) {
    set(serverTimeMs !== undefined ? { connected, clockOffsetMs: serverTimeMs - Date.now() } : { connected });
  },

  pushFeed(item) {
    set({ feed: [item, ...get().feed].slice(0, FEED_LIMIT) });
  },

  setLeaderboard(rows) {
    set({ leaderboard: rows.slice(0, 5) });
  },

  reset() {
    set({ seats: new Map(), feed: [], leaderboard: [], buffer: [] });
  },
}));

export function countSeats(seats: Map<string, SeatEntry>): { available: number; held: number; sold: number } {
  let held = 0;
  let sold = 0;
  for (const s of seats.values()) {
    if (s.state === 'HELD') held++;
    else if (s.state === 'SOLD') sold++;
  }
  return { available: seats.size - held - sold, held, sold };
}
