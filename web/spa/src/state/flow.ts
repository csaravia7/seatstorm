import { api } from '../api/endpoints';
import { ApiError, NetworkError } from '../api/client';
import type { MeMessage } from '../api/types';
import { clearIdempotencyKey, idempotencyKeyFor } from '../lib/idempotency';
import { useMapStore } from './mapStore';
import { ACTIVE_STATUSES, normalizeOrder } from './orderLogic';
import { usePurchaseStore } from './purchaseStore';

const ps = () => usePurchaseStore.getState();

export function selectionTotal(): number {
  const { selection } = ps();
  const price = selection.sectionId ? (useMapStore.getState().priceBySection[selection.sectionId] ?? 0) : 0;
  return price * selection.seatIds.length;
}

/** POST /api/orders with a stable Idempotency-Key: a retry of the same seats replays the same order. */
export async function buy(): Promise<void> {
  const { selection } = ps();
  if (!selection.sectionId || selection.seatIds.length === 0) return;
  const key = idempotencyKeyFor(selection.sectionId, selection.seatIds);
  ps().startSubmit(selectionTotal());
  try {
    const res = await api.createOrder(selection.sectionId, selection.seatIds, key);
    ps().submitted(res.orderId);
  } catch (err) {
    ps().setOrder(null);
    if (err instanceof ApiError) {
      clearIdempotencyKey();
      if (err.code === 'ACTIVE_ORDER_EXISTS') {
        await restoreActiveOrder();
        ps().showToast('warn', 'Ya tienes una compra en curso.');
      } else if (err.code === 'SALE_CLOSED') ps().setNotice('La venta está cerrada por ahora.');
      else if (err.code === 'RATE_LIMITED') ps().showToast('warn', 'Vas muy rápido. Espera unos segundos.');
      else ps().showToast('error', err.message);
    } else if (err instanceof NetworkError) {
      // Keep the key: pressing Comprar again replays the same request.
      ps().showToast('error', 'Sin conexión. Vuelve a pulsar Comprar.');
    }
  }
}

export async function pay(): Promise<void> {
  const o = ps().order;
  if (!o?.orderId) return;
  try {
    await api.pay(o.orderId);
    if (ps().order?.status === 'HELD') ps().patchOrder({ status: 'PAYING' });
  } catch (err) {
    if (err instanceof ApiError && err.code === 'HOLD_EXPIRED') ps().patchOrder({ status: 'EXPIRED', reason: 'HOLD_EXPIRED' });
    else ps().showToast('error', err instanceof ApiError ? err.message : 'No pudimos enviar el pago. Inténtalo de nuevo.');
  }
}

export async function cancel(): Promise<void> {
  const o = ps().order;
  if (!o?.orderId) return;
  try {
    await api.cancel(o.orderId);
  } catch (err) {
    ps().showToast('error', err instanceof ApiError ? err.message : 'No pudimos cancelar. Inténtalo de nuevo.');
  }
}

export function handleMe(msg: MeMessage): void {
  if (msg.type === 'order') ps().applyOrderMessage(msg);
  else if (msg.type === 'notify') ps().showToast(msg.level, msg.text);
}

/** After a reload, pick up an order that is still in flight. */
export async function restoreActiveOrder(): Promise<void> {
  try {
    const list = await api.myOrders();
    const raw = list.find((o) => ACTIVE_STATUSES.has(String(o.status)));
    const n = raw ? normalizeOrder(raw) : null;
    if (n) {
      ps().setOrder({
        orderId: n.orderId,
        status: n.status!,
        reason: n.reason ?? null,
        heldUntilMs: n.heldUntilMs ?? null,
        sectionId: n.sectionId ?? '',
        seatIds: n.seatIds ?? [],
        totalCents: n.totalCents ?? 0,
        ticketQr: n.ticketQr,
      });
    }
  } catch {
    // not fatal
  }
}

/** The QR comes from seatmap-query (TicketIssued is asynchronous), so poll until it shows up. */
export async function fetchTicket(orderId: string, tries = 10, delayMs = 1000): Promise<void> {
  for (let i = 0; i < tries; i++) {
    try {
      const list = await api.myOrders();
      const raw = list.find((o) => (o.orderId ?? o.order_id) === orderId);
      const n = raw ? normalizeOrder(raw) : null;
      if (n?.ticketQr) {
        if (ps().order?.orderId === orderId) ps().patchOrder({ ticketQr: n.ticketQr, seatIds: n.seatIds?.length ? n.seatIds : ps().order!.seatIds });
        return;
      }
    } catch {
      // retry
    }
    await new Promise((r) => setTimeout(r, delayMs));
  }
}

// ---- Fallback poller -------------------------------------------------------------------------
// The live channel (SSE) can be buffered or dropped by proxies (e.g. Codespaces port forwarding), which left the
// buyer on "Procesando tu pago…" forever. While an order is in flight we also ask the server directly.
const IN_FLIGHT = new Set(['PLACED', 'HELD', 'PAYING', 'CONFIRMING', 'REFUNDING']);
const POLL_MS = 2000;
const SLOW_PAYMENT_MS = 30_000;

let watcher: ReturnType<typeof setInterval> | undefined;
let inFlightSince = 0;
let slowWarnedFor: string | null = null;

async function pollOrderOnce(): Promise<void> {
  const o = ps().order;
  if (!o?.orderId || !IN_FLIGHT.has(String(o.status))) {
    inFlightSince = 0;
    return;
  }
  if (!inFlightSince) inFlightSince = Date.now();
  try {
    const raw = await api.order(o.orderId);
    const n = normalizeOrder(raw);
    if (n?.status && n.status !== o.status) {
      ps().applyOrderMessage({
        type: 'order',
        buyerId: '',
        orderId: o.orderId,
        status: n.status,
        reason: n.reason ?? null,
        heldUntilMs: n.heldUntilMs ?? null,
      });
      inFlightSince = Date.now();
    }
  } catch {
    // transient: the next tick retries
  }
  const cur = ps().order;
  if (
    cur &&
    (cur.status === 'PAYING' || cur.status === 'CONFIRMING') &&
    Date.now() - inFlightSince > SLOW_PAYMENT_MS &&
    slowWarnedFor !== cur.orderId
  ) {
    slowWarnedFor = cur.orderId;
    ps().showToast('warn', 'El pago está tardando más de lo normal. Seguimos intentando; si no se completa se cancelará y se liberarán tus asientos.');
  }
}

/** Idempotent; call once when the buyer page mounts. */
export function startOrderWatcher(): () => void {
  if (!watcher) watcher = setInterval(() => void pollOrderOnce(), POLL_MS);
  return () => {
    if (watcher) clearInterval(watcher);
    watcher = undefined;
  };
}
