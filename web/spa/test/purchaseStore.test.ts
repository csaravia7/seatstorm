import { beforeEach, describe, expect, it } from 'vitest';
import { usePurchaseStore } from '../src/state/purchaseStore';
import { clearIdempotencyKey, idempotencyKeyFor } from '../src/lib/idempotency';

beforeEach(() => {
  usePurchaseStore.setState({ selection: { sectionId: null, seatIds: [] }, order: null, notice: null, toast: null });
  sessionStorage.clear();
});

describe('seat selection', () => {
  it('allows 1 to 4 seats of one section', () => {
    const s = () => usePurchaseStore.getState();
    for (let i = 1; i <= 4; i++) expect(s().toggleSeat(`PLATEA-A:A0${i}`)).toBe('added');
    expect(s().selection.seatIds).toHaveLength(4);
    expect(s().toggleSeat('PLATEA-A:A05')).toBe('max');
    expect(s().selection.seatIds).toHaveLength(4);
  });

  it('refuses a seat of another section', () => {
    const s = () => usePurchaseStore.getState();
    s().toggleSeat('PLATEA-A:A01');
    expect(s().toggleSeat('PALCO-1:A01')).toBe('other-section');
    expect(s().selection).toEqual({ sectionId: 'PLATEA-A', seatIds: ['PLATEA-A:A01'] });
  });

  it('toggles a seat off and frees the section lock when empty', () => {
    const s = () => usePurchaseStore.getState();
    s().toggleSeat('PLATEA-A:A01');
    expect(s().toggleSeat('PLATEA-A:A01')).toBe('removed');
    expect(s().selection.sectionId).toBeNull();
    expect(s().toggleSeat('PALCO-1:A01')).toBe('added');
  });
});

describe('Idempotency-Key', () => {
  it('is reused for a retry of the same seats (stored in sessionStorage)', () => {
    const k1 = idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A02', 'PLATEA-A:A01']);
    const k2 = idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A01', 'PLATEA-A:A02']);
    expect(k2).toBe(k1);
    expect(sessionStorage.getItem('ss.idem')).toContain(k1);
  });
  it('changes for different seats or after being cleared', () => {
    const k1 = idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A01']);
    expect(idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A03'])).not.toBe(k1);
    const k3 = idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A03']);
    clearIdempotencyKey();
    expect(idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A03'])).not.toBe(k3);
  });
});
