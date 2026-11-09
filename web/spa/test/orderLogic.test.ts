import { describe, expect, it } from 'vitest';
import { applySubmitted, normalizeOrder, outcomeNotice, reduceOrder, type ActiveOrder } from '../src/state/orderLogic';
import type { OrderMessage } from '../src/api/types';

const base: ActiveOrder = { orderId: 'o1', status: 'PLACED', reason: null, heldUntilMs: null, sectionId: 'PLATEA-A', seatIds: ['PLATEA-A:A01'], totalCents: 9000 };
const msg = (over: Partial<OrderMessage>): OrderMessage => ({ type: 'order', buyerId: 'b', orderId: 'o1', status: 'HELD', reason: null, heldUntilMs: 123, ...over });

describe('order reducer', () => {
  it('walks PLACED -> HELD -> PAYING -> CONFIRMED', () => {
    let o: ActiveOrder | null = base;
    o = reduceOrder(o, msg({ status: 'HELD' }));
    expect(o).toMatchObject({ status: 'HELD', heldUntilMs: 123 });
    o = reduceOrder(o, msg({ status: 'PAYING', heldUntilMs: null }));
    expect(o).toMatchObject({ status: 'PAYING', heldUntilMs: 123 });
    o = reduceOrder(o, msg({ status: 'CONFIRMED' }));
    expect(o!.status).toBe('CONFIRMED');
  });

  it('ignores messages for other orders and after a final state', () => {
    expect(reduceOrder(base, msg({ orderId: 'zzz' }))).toBe(base);
    const done = { ...base, status: 'EXPIRED' as const };
    expect(reduceOrder(done, msg({ status: 'HELD' }))).toBe(done);
  });

  it('adopts the first message while the POST response is pending (event beats response)', () => {
    const submitting: ActiveOrder = { ...base, orderId: null, status: 'SUBMITTING' };
    const adopted = reduceOrder(submitting, msg({ orderId: 'o9', status: 'HELD' }))!;
    expect(adopted).toMatchObject({ orderId: 'o9', status: 'HELD' });
    expect(applySubmitted(adopted, 'o9')!.status).toBe('HELD'); // does not regress to PLACED
    expect(applySubmitted(submitting, 'o9')).toMatchObject({ orderId: 'o9', status: 'PLACED' });
  });

  it('keeps REJECTED/PAYMENT_FAILED reasons and maps them to notices', () => {
    const r = reduceOrder(base, msg({ status: 'REJECTED', reason: 'SEAT_TAKEN' }))!;
    expect(outcomeNotice(r)).toBe('Alguien llegó antes. Elige otros asientos.');
    const c = reduceOrder(base, msg({ status: 'CANCELLED', reason: 'PAYMENT_FAILED' }))!;
    expect(outcomeNotice(c)).toMatch(/pago/);
    expect(outcomeNotice({ status: 'CANCELLED', reason: 'HOLD_EXPIRED' })).toBeNull();
    expect(outcomeNotice({ status: 'HELD', reason: null })).toBeNull();
  });

  it('normalizes order DTOs in camelCase or snake_case', () => {
    const a = normalizeOrder({ orderId: 'x', status: 'CONFIRMED', seatIds: ['s'], totalCents: 5, heldUntil: '2026-11-09T10:00:00Z', ticketQr: 'QR' })!;
    expect(a).toMatchObject({ orderId: 'x', ticketQr: 'QR', totalCents: 5, heldUntilMs: Date.parse('2026-11-09T10:00:00Z') });
    const b = normalizeOrder({ order_id: 'y', status: 'HELD', seat_ids: ['q'], total_cents: 7, section_id: 'S', ticket_qr: 'Z' })!;
    expect(b).toMatchObject({ orderId: 'y', seatIds: ['q'], totalCents: 7, sectionId: 'S', ticketQr: 'Z' });
    expect(normalizeOrder({})).toBeNull();
  });
});
