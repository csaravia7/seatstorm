import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { Hub, MAX_QUEUED_BYTES, type LiveConnection, type View } from '../src/live/hub.js';

function conn(id: number, buyerId: string | null, view: View = 'public') {
  const written: string[] = [];
  let queued = 0;
  let closed = false;
  const c: LiveConnection & { written: string[]; setQueued(n: number): void; closed(): boolean } = {
    id, buyerId, view, written,
    write: (s) => void written.push(s),
    queued: () => queued,
    close: () => { closed = true; },
    setQueued: (n) => { queued = n; },
    closed: () => closed,
  };
  return c;
}
const parse = (frame: string) => {
  const [ev, data] = frame.trim().split('\n');
  return { event: ev!.slice(7), data: JSON.parse(data!.slice(6)) };
};
const mk = (max = 10) => new Hub({ maxConnections: max, seatFlushMs: 100, pingMs: 15000 });
const seat = (seatId: string, version: number, state = 'HELD') => ({ type: 'seat', sectionId: 'PLATEA-A', seatId, state, version, concertId: 'c' });

describe('Hub', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('coalesces seat diffs received within 100 ms into one event, highest version wins', () => {
    const hub = mk();
    const a = conn(1, null);
    hub.add(a);
    hub.start();
    hub.handleUiEvent(seat('PLATEA-A:A01', 3));
    hub.handleUiEvent(seat('PLATEA-A:A01', 5, 'SOLD'));
    hub.handleUiEvent(seat('PLATEA-A:A01', 4));
    hub.handleUiEvent(seat('PLATEA-A:A02', 1));
    hub.handleUiEvent(seat('PLATEA-A:A03', 1));
    hub.handleUiEvent(seat('PLATEA-A:A04', 1));
    hub.handleUiEvent(seat('PLATEA-A:A05', 1));
    expect(a.written).toHaveLength(0);
    vi.advanceTimersByTime(100);
    expect(a.written).toHaveLength(1);
    const { event, data } = parse(a.written[0]!);
    expect(event).toBe('seat');
    expect(data).toHaveLength(5);
    expect(data.find((d: { seatId: string }) => d.seatId === 'PLATEA-A:A01')).toMatchObject({ version: 5, state: 'SOLD' });
    vi.advanceTimersByTime(300);
    expect(a.written).toHaveLength(1); // nothing pending: no empty events
    hub.stop();
  });

  it('routes order/notify only to the owning buyer as `me`', () => {
    const hub = mk();
    const a1 = conn(1, 'A');
    const a2 = conn(2, 'A');
    const b = conn(3, 'B');
    const anon = conn(4, null);
    [a1, a2, b, anon].forEach((c) => hub.add(c));
    hub.handleUiEvent({ type: 'order', buyerId: 'A', orderId: 'o', status: 'HELD' });
    hub.handleUiEvent({ type: 'notify', buyerId: 'A', level: 'success', text: 'ok' });
    expect(a1.written.map((f) => parse(f).event)).toEqual(['me', 'me']);
    expect(a2.written).toHaveLength(2);
    expect(b.written).toHaveLength(0);
    expect(anon.written).toHaveLength(0);
    expect(parse(a1.written[0]!).data.status).toBe('HELD');
  });

  it('sends feed only to stage views and sale/reset to everyone', () => {
    const hub = mk();
    const pub = conn(1, null, 'public');
    const stage = conn(2, null, 'stage');
    hub.add(pub);
    hub.add(stage);
    hub.handleUiEvent({ type: 'feed', name: 'SeatsRejected', detail: 'x', at: 1 });
    expect(pub.written).toHaveLength(0);
    expect(parse(stage.written[0]!).event).toBe('feed');
    hub.handleUiEvent({ type: 'sale', status: 'OPEN' });
    hub.handleUiEvent({ type: 'reset' });
    expect(pub.written.map((f) => parse(f).event)).toEqual(['sale', 'reset']);
    expect(parse(pub.written[0]!).data).toEqual({ status: 'OPEN' });
    expect(stage.written).toHaveLength(3);
    expect(hub.stageCount()).toBe(1);
  });

  it('drops pending seat diffs on reset', () => {
    const hub = mk();
    const a = conn(1, null);
    hub.add(a);
    hub.handleUiEvent(seat('PLATEA-A:A01', 9));
    hub.handleUiEvent({ type: 'reset' });
    hub.flushSeats();
    expect(a.written.map((f) => parse(f).event)).toEqual(['reset']);
  });

  it('enforces the connection limit and frees slots on remove', () => {
    const hub = mk(2);
    const [a, b, c] = [conn(1, null), conn(2, null), conn(3, 'X')];
    expect(hub.add(a)).toBe(true);
    expect(hub.add(b)).toBe(true);
    expect(hub.add(c)).toBe(false);
    hub.remove(a);
    expect(hub.add(c)).toBe(true);
    expect(hub.size).toBe(2);
  });

  it('disconnects slow clients with more than 1 MB queued', () => {
    const hub = mk();
    const slow = conn(1, 'S');
    const fast = conn(2, null);
    hub.add(slow);
    hub.add(fast);
    slow.setQueued(MAX_QUEUED_BYTES + 1);
    hub.handleUiEvent({ type: 'sale', status: 'OPEN' });
    expect(slow.closed()).toBe(true);
    expect(slow.written).toHaveLength(0);
    expect(hub.size).toBe(1);
    expect(fast.written).toHaveLength(1);
  });

  it('pings every 15 s', () => {
    const hub = mk();
    const a = conn(1, null);
    hub.add(a);
    hub.start();
    vi.advanceTimersByTime(15000);
    expect(a.written.filter((f) => f === ': ping\n\n')).toHaveLength(1);
    hub.stop();
  });

  it('ignores malformed events', () => {
    const hub = mk();
    const a = conn(1, 'A');
    hub.add(a);
    hub.handleUiEvent({ type: 'seat' });
    hub.handleUiEvent({ type: 'order' });
    hub.handleUiEvent({ type: 'weird' });
    hub.flushSeats();
    expect(a.written).toHaveLength(0);
  });
});
