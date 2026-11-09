import type { OrderMessage, OrderStatus } from '../api/types';

export interface ActiveOrder {
  /** null while the POST /api/orders response has not arrived yet. */
  orderId: string | null;
  status: OrderStatus;
  reason: string | null;
  heldUntilMs: number | null;
  sectionId: string;
  seatIds: string[];
  totalCents: number;
  ticketQr?: string | null;
}

export const FINAL_STATUSES: ReadonlySet<OrderStatus> = new Set(['CONFIRMED', 'REJECTED', 'CANCELLED', 'EXPIRED']);
export const ACTIVE_STATUSES: ReadonlySet<string> = new Set(['PLACED', 'HELD', 'PAYING', 'CONFIRMING', 'REFUNDING']);

export const isFinal = (s: OrderStatus) => FINAL_STATUSES.has(s);

/**
 * Applies an `order` message from the `me` SSE stream.
 * - Messages for another orderId are ignored.
 * - While submitting (orderId still unknown) the first message is adopted: the event can win the race against the POST response.
 * - A final state is never overwritten.
 */
export function reduceOrder(cur: ActiveOrder | null, msg: OrderMessage): ActiveOrder | null {
  if (!cur) return cur;
  if (cur.orderId !== null && cur.orderId !== msg.orderId) return cur;
  if (isFinal(cur.status)) return cur;
  return {
    ...cur,
    orderId: msg.orderId,
    status: msg.status,
    reason: msg.reason ?? null,
    heldUntilMs: msg.heldUntilMs ?? cur.heldUntilMs,
  };
}

/** Called when POST /api/orders answers; never regresses a status already learned from SSE. */
export function applySubmitted(cur: ActiveOrder | null, orderId: string): ActiveOrder | null {
  if (!cur) return cur;
  if (cur.orderId === null) return { ...cur, orderId, status: cur.status === 'SUBMITTING' ? 'PLACED' : cur.status };
  return cur;
}

/** Message shown when an order ends in a state that sends the buyer back to the map. */
export function outcomeNotice(o: Pick<ActiveOrder, 'status' | 'reason'>): string | null {
  switch (o.status) {
    case 'REJECTED':
      return 'Alguien llegó antes. Elige otros asientos.';
    case 'CANCELLED':
      if (o.reason === 'PAYMENT_FAILED') return 'No pudimos procesar el pago. Inténtalo de nuevo.';
      if (o.reason === 'USER_CANCELLED') return 'Cancelaste tu reserva.';
      return null; // HOLD_EXPIRED / RESET have their own screens
    default:
      return null;
  }
}

const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);

/** Tolerant reader for order DTOs (camelCase or snake_case; held-until as ms or ISO). */
export function normalizeOrder(raw: Record<string, unknown>): Partial<ActiveOrder> & { orderId: string; ticketQr: string | null } | null {
  const orderId = str(raw.orderId) ?? str(raw.order_id);
  const status = str(raw.status) as OrderStatus | null;
  if (!orderId || !status) return null;
  let heldUntilMs = num(raw.heldUntilMs);
  const iso = str(raw.heldUntil) ?? str(raw.held_until);
  if (heldUntilMs === null && iso) {
    const t = Date.parse(iso);
    heldUntilMs = Number.isNaN(t) ? null : t;
  }
  const seatIds = (Array.isArray(raw.seatIds) ? raw.seatIds : Array.isArray(raw.seat_ids) ? raw.seat_ids : []) as string[];
  return {
    orderId,
    status,
    reason: str(raw.reason),
    heldUntilMs,
    sectionId: str(raw.sectionId) ?? str(raw.section_id) ?? '',
    seatIds,
    totalCents: num(raw.totalCents) ?? num(raw.total_cents) ?? 0,
    ticketQr: str(raw.ticketQr) ?? str(raw.ticket_qr),
  };
}
