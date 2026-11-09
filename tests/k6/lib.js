// Shared helpers for smoke.js and load.js (no remote imports: CI and venues may be offline).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
// Load tools present ADMIN_TOKEN in X-Bot-Token so POST /api/session is not throttled per IP.
export const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || 'change-me-admin';
export const CONSOLE_PIN = __ENV.CONSOLE_PIN || '4321';
export const CONCERT_ID = 'neon-tide-2026';

export const SECTIONS = [
  'PLATEA-A', 'PLATEA-B', 'PLATEA-C', 'PLATEA-D',
  'PALCO-1', 'PALCO-2', 'PALCO-3', 'PALCO-4',
  'GRADA-N', 'GRADA-S', 'GRADA-E', 'GRADA-O',
];
export const ROWS = ['A', 'B', 'C'];

export const ordersConfirmed = new Counter('orders_confirmed');
export const ordersRejected = new Counter('orders_rejected');
export const ordersPending = new Counter('orders_pending');
export const timeToFinal = new Trend('order_time_to_final_ms', true);

// Only 200/202 are expected answers; anything else counts as a failed request.
http.setResponseCallback(http.expectedStatuses(200, 202));

export const seatId = (section, row, n) => `${section}:${row}${String(n).padStart(2, '0')}`;

export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/** Presenter login, then open the sale. Returns nothing; safe to call when the sale is already open. */
export function openSale() {
  const login = http.post(`${BASE_URL}/admin/login`, JSON.stringify({ pin: CONSOLE_PIN }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'admin_login' },
  });
  if (!check(login, { 'admin login 200': (r) => r.status === 200 })) {
    throw new Error(`admin login failed (${login.status}); check CONSOLE_PIN`);
  }
  const cookie = `ss_admin=${login.cookies.ss_admin[0].value}`;
  const open = http.post(`${BASE_URL}/admin/sale/open`, '{}', {
    headers: { 'Content-Type': 'application/json', Cookie: cookie },
    tags: { name: 'sale_open' },
  });
  check(open, { 'sale open 2xx': (r) => r.status === 200 || r.status === 202 });
  sleep(2); // let SaleOpened reach order-service
}

/** Creates the buyer session in this VU's cookie jar. */
export function joinAs(nickname) {
  const res = http.post(`${BASE_URL}/api/session`, JSON.stringify({ nickname }), {
    headers: { 'Content-Type': 'application/json', 'X-Bot-Token': ADMIN_TOKEN },
    tags: { name: 'create_session' },
  });
  return check(res, { 'session 200': (r) => r.status === 200 });
}

/**
 * Places an order, pays when held, and waits for the outcome.
 * `pick` = { sectionId, seatIds }. Records metrics; returns the final status (or 'PENDING').
 */
export function buy(pick, { pay = true, maxWaitSeconds = 20 } = {}) {
  const key = uuid();
  const started = Date.now();
  const create = http.post(
    `${BASE_URL}/api/orders`,
    JSON.stringify({ concertId: CONCERT_ID, sectionId: pick.sectionId, seatIds: pick.seatIds }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, tags: { name: 'create_order' } },
  );
  if (!check(create, { 'order accepted': (r) => r.status === 202 || r.status === 200 })) return 'ERROR';
  const orderId = create.json('orderId');

  let paid = false;
  const deadline = Date.now() + maxWaitSeconds * 1000;
  while (Date.now() < deadline) {
    sleep(0.5 + Math.random() * 0.3);
    const r = http.get(`${BASE_URL}/api/orders/${orderId}`, { tags: { name: 'get_order' } });
    if (r.status !== 200) continue;
    const status = r.json('status');
    if (status === 'CONFIRMED') {
      ordersConfirmed.add(1);
      timeToFinal.add(Date.now() - started);
      return status;
    }
    if (status === 'REJECTED' || status === 'CANCELLED' || status === 'EXPIRED') {
      ordersRejected.add(1);
      timeToFinal.add(Date.now() - started);
      return status;
    }
    if (status === 'HELD' && pay && !paid) {
      paid = true;
      http.post(`${BASE_URL}/api/orders/${orderId}/pay`, JSON.stringify({ method: 'CARD_SIM' }), {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'pay_order' },
      });
    }
  }
  ordersPending.add(1);
  return 'PENDING';
}
