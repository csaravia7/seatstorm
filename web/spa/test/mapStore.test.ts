import { beforeEach, describe, expect, it } from 'vitest';
import { countSeats, useMapStore } from '../src/state/mapStore';
import type { SeatmapDto } from '../src/api/types';

const map = (version: number, state: 'AVAILABLE' | 'HELD' | 'SOLD' = 'AVAILABLE'): SeatmapDto => ({
  concertId: 'c',
  asOfMs: 1,
  sections: [
    { sectionId: 'PLATEA-A', index: 0, label: 'Platea A', priceCents: 9000, seats: [
      { seatId: 'PLATEA-A:A01', row: 'A', number: 1, state, version },
      { seatId: 'PLATEA-A:A02', row: 'A', number: 2, state: 'AVAILABLE', version: 0 },
    ] },
  ],
});

beforeEach(() => useMapStore.setState({ seats: new Map(), saleStatus: null, openingUntil: null, feed: [], loading: false, buffer: [] }));

describe('map store', () => {
  it('applies a diff only when its version is greater', () => {
    const s = useMapStore.getState();
    s.loadSeatmap(map(5, 'HELD'));
    useMapStore.getState().applyDiffs([{ seatId: 'PLATEA-A:A01', state: 'AVAILABLE', version: 4 }]);
    expect(useMapStore.getState().seats.get('PLATEA-A:A01')).toEqual({ state: 'HELD', version: 5 });
    useMapStore.getState().applyDiffs([{ seatId: 'PLATEA-A:A01', state: 'AVAILABLE', version: 5 }]);
    expect(useMapStore.getState().seats.get('PLATEA-A:A01')!.state).toBe('HELD');
    useMapStore.getState().applyDiffs([{ seatId: 'PLATEA-A:A01', state: 'SOLD', version: 6 }]);
    expect(useMapStore.getState().seats.get('PLATEA-A:A01')).toEqual({ state: 'SOLD', version: 6 });
  });

  it('keeps unchanged Map references untouched when nothing applies', () => {
    useMapStore.getState().loadSeatmap(map(5));
    const before = useMapStore.getState().seats;
    useMapStore.getState().applyDiffs([{ seatId: 'PLATEA-A:A01', state: 'HELD', version: 1 }]);
    expect(useMapStore.getState().seats).toBe(before);
  });

  it('preserves entry identity for seats that did not change (memoized rects stay still)', () => {
    useMapStore.getState().loadSeatmap(map(5));
    const e2 = useMapStore.getState().seats.get('PLATEA-A:A02');
    useMapStore.getState().applyDiffs([{ seatId: 'PLATEA-A:A01', state: 'HELD', version: 6 }]);
    expect(useMapStore.getState().seats.get('PLATEA-A:A02')).toBe(e2);
  });

  it('re-applies diffs that arrived while the snapshot was loading', () => {
    useMapStore.getState().beginLoad();
    useMapStore.getState().applyDiffs([{ seatId: 'PLATEA-A:A01', state: 'SOLD', version: 9 }]);
    useMapStore.getState().loadSeatmap(map(3, 'HELD')); // older snapshot
    expect(useMapStore.getState().seats.get('PLATEA-A:A01')).toEqual({ state: 'SOLD', version: 9 });
    expect(useMapStore.getState().loading).toBe(false);
  });

  it('reset forgets versions so a fresh snapshot with lower versions wins', () => {
    useMapStore.getState().loadSeatmap(map(50, 'SOLD'));
    useMapStore.getState().reset();
    useMapStore.getState().loadSeatmap(map(0));
    expect(useMapStore.getState().seats.get('PLATEA-A:A01')).toEqual({ state: 'AVAILABLE', version: 0 });
  });

  it('counts seats by state', () => {
    useMapStore.getState().loadSeatmap(map(1, 'HELD'));
    expect(countSeats(useMapStore.getState().seats)).toEqual({ available: 1, held: 1, sold: 0 });
  });

  it('starts the 3 s opening countdown only on a live CLOSED -> OPEN transition', () => {
    const s = useMapStore.getState();
    s.setSale('CLOSED', false);
    s.setSale('OPEN', false);
    expect(useMapStore.getState().openingUntil).toBeNull();
    useMapStore.getState().setSale('CLOSED', true);
    useMapStore.getState().setSale('OPEN', true);
    const until = useMapStore.getState().openingUntil!;
    expect(until - Date.now()).toBeGreaterThan(2500);
    expect(until - Date.now()).toBeLessThanOrEqual(3000);
  });

  it('keeps only the last 12 feed items, newest first', () => {
    for (let i = 0; i < 15; i++) useMapStore.getState().pushFeed({ name: 'n', detail: String(i), at: i });
    const feed = useMapStore.getState().feed;
    expect(feed).toHaveLength(12);
    expect(feed[0]!.detail).toBe('14');
  });
});
