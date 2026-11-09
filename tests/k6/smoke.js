// Smoke test: 20 virtual users, one order each, on distinct seats (no contention).
// Usage: k6 run -e BASE_URL=http://localhost:8080 -e CONSOLE_PIN=4321 -e ADMIN_TOKEN=... tests/k6/smoke.js
import { SECTIONS, ROWS, joinAs, openSale, buy, seatId } from './lib.js';

export const options = {
  scenarios: {
    smoke: { executor: 'per-vu-iterations', vus: 20, iterations: 1, maxDuration: '90s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:create_order}': ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  openSale();
}

export default function () {
  const i = __VU - 1;
  if (!joinAs(`smoke-${String(__VU).padStart(3, '0')}`)) return;
  // One distinct seat per VU: spread over sections first, then rows.
  const section = SECTIONS[i % SECTIONS.length];
  const row = ROWS[Math.floor(i / SECTIONS.length) % ROWS.length];
  buy({ sectionId: section, seatIds: [seatId(section, row, 1)] });
}
