// Load test: 1 000 buyers in a burst competing for 360 seats (contention is the point).
// Run tests/invariants.sh afterwards, once consumer lag is 0.
// Usage: k6 run -e BASE_URL=http://localhost:8080 -e CONSOLE_PIN=4321 -e ADMIN_TOKEN=... tests/k6/load.js
import { sleep } from 'k6';
import { SECTIONS, ROWS, joinAs, openSale, buy, seatId } from './lib.js';

const BUYERS = Number(__ENV.BUYERS || 1000);

export const options = {
  scenarios: {
    burst: { executor: 'per-vu-iterations', vus: BUYERS, iterations: 1, maxDuration: '5m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:create_order}': ['p(95)<1500'],
  },
};

export function setup() {
  openSale();
}

const rnd = (n) => Math.floor(Math.random() * n);

export default function () {
  sleep(Math.random() * 2); // everyone arrives within ~2 s
  if (!joinAs(`load-${String(__VU).padStart(4, '0')}`)) return;
  const sectionId = SECTIONS[rnd(SECTIONS.length)];
  const row = ROWS[rnd(ROWS.length)];
  const qty = 1 + rnd(2);
  const start = 1 + rnd(10 - qty + 1);
  const seatIds = [];
  for (let k = 0; k < qty; k++) seatIds.push(seatId(sectionId, row, start + k));
  buy({ sectionId, seatIds }, { pay: true, maxWaitSeconds: 30 });
}
