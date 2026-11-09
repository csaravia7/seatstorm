import { describe, expect, it } from 'vitest';
import { Swarm } from '../src/swarm.js';
import { buildApp } from '../src/app.js';
import { pickSeats } from '../src/layout.js';

/** In-memory BFF: first order of each section-seat wins, the rest are rejected. */
function fakeBff(opts: { saleOpen?: boolean; throttleFirst?: number; hold?: boolean } = {}) {
  const taken = new Set<string>();
  const orders = new Map<string, { status: string }>();
  let throttled = 0;
  const log: string[] = [];
  let sessions = 0;
  const f = (async (url: string, init: RequestInit = {}) => {
    const path = new URL(url).pathname;
    const method = init.method ?? 'GET';
    log.push(`${method} ${path}`);
    const h = init.headers as Record<string, string>;
    const reply = (b: unknown, status = 200, headers: Record<string, string> = {}) =>
      new Response(JSON.stringify(b), { status, headers: { 'content-type': 'application/json', ...headers } });
    if (path === '/api/session') {
      expect(h['x-bot-token']).toBe('tok');
      sessions++;
      return reply({ buyerId: `b_${sessions}` }, 200, { 'set-cookie': `ss_sid=abc${sessions}; Path=/; HttpOnly` });
    }
    if (path === '/api/orders' && method === 'POST') {
      expect(h.cookie).toMatch(/^ss_sid=abc/);
      expect(h['idempotency-key']).toBeTruthy();
      if (throttled < (opts.throttleFirst ?? 0)) { throttled++; return reply({ code: 'RATE_LIMITED' }, 429); }
      if (opts.saleOpen === false) return reply({ code: 'SALE_CLOSED' }, 409);
      const body = JSON.parse(init.body as string);
      const lost = body.seatIds.some((s: string) => taken.has(s));
      body.seatIds.forEach((s: string) => taken.add(s));
      const id = `o${orders.size}`;
      orders.set(id, { status: lost ? 'REJECTED' : 'HELD' });
      return reply({ orderId: id, status: 'PLACED' }, 202);
    }
    const m = path.match(/^\/api\/orders\/(o\d+)(\/pay)?$/);
    if (m) {
      const o = orders.get(m[1]!)!;
      if (m[2]) { o.status = 'CONFIRMED'; return reply({ status: 'PAYING' }, 202); }
      return reply({ status: o.status });
    }
    return reply({}, 404);
  }) as typeof fetch;
  return { f, log };
}

const mk = (bff: ReturnType<typeof fakeBff>, rng = Math.random) =>
  new Swarm({ bffUrl: 'http://bff', adminToken: 'tok', fetchImpl: bff.f, sleep: async () => undefined, rng, pollMs: 0, pollJitterMs: 0, backoffBaseMs: 0 });

const settle = async (s: Swarm) => { for (let i = 0; i < 400 && s.status().running; i++) await new Promise((r) => setTimeout(r, 5)); };
const opts = { count: 20, rampSeconds: 1, profile: 'burst' as const, payProbability: 1 };

describe('Swarm', () => {
  it('creates sessions and orders and counts outcomes', async () => {
    const bff = fakeBff();
    const s = mk(bff);
    expect(s.start(opts)).toBe(true);
    await settle(s);
    const st = s.status();
    expect(bff.log.filter((l) => l === 'POST /api/session')).toHaveLength(20);
    expect(st.placed).toBe(20);
    expect(st.confirmed + st.rejected).toBe(20);
    expect(st.confirmed).toBeGreaterThan(0);
    expect(st.errors).toBe(0);
    expect(st.active).toBe(0);
    expect(st.running).toBe(false);
  });

  it('does not pay when payProbability is 0', async () => {
    const bff = fakeBff();
    const s = mk(bff);
    s.start({ ...opts, payProbability: 0 });
    await settle(s);
    expect(bff.log.some((l) => l.endsWith('/pay'))).toBe(false);
    expect(s.status().confirmed).toBe(0);
  });

  it('retries 429 with backoff, at most 3 times', async () => {
    const bff = fakeBff({ throttleFirst: 3 });
    const s = mk(bff);
    s.start({ ...opts, count: 1 });
    await settle(s);
    expect(s.status().placed).toBe(1);

    const bff2 = fakeBff({ throttleFirst: 100 });
    const s2 = mk(bff2);
    s2.start({ ...opts, count: 1 });
    await settle(s2);
    expect(bff2.log.filter((l) => l === 'POST /api/orders')).toHaveLength(4); // 1 + 3 retries
    expect(s2.status()).toMatchObject({ placed: 0, errors: 1 });
  });

  it('counts a closed sale as an error', async () => {
    const s = mk(fakeBff({ saleOpen: false }));
    s.start({ ...opts, count: 3 });
    await settle(s);
    expect(s.status()).toMatchObject({ placed: 0, errors: 3 });
  });

  it('stop() halts quickly even with real sleeps', async () => {
    const bff = fakeBff();
    const s = new Swarm({ bffUrl: 'http://bff', adminToken: 'tok', fetchImpl: bff.f });
    s.start({ count: 50, rampSeconds: 30, profile: 'steady', payProbability: 1 });
    await new Promise((r) => setTimeout(r, 50));
    const t = Date.now();
    s.stop();
    for (let i = 0; i < 100 && s.status().active > 0; i++) await new Promise((r) => setTimeout(r, 10));
    expect(Date.now() - t).toBeLessThan(2000);
    expect(s.status()).toMatchObject({ running: false, active: 0 });
  });

  it('refuses to start twice while running', async () => {
    const s = new Swarm({ bffUrl: 'http://bff', adminToken: 'tok', fetchImpl: fakeBff().f });
    expect(s.start({ count: 5, rampSeconds: 30, profile: 'steady', payProbability: 1 })).toBe(true);
    expect(s.start(opts)).toBe(false);
    s.stop();
  });

  it('greedy retries on rejection', async () => {
    const bff = fakeBff();
    const s = mk(bff, () => 0); // same seats every time: first wins, others retry the same seats until attempts run out
    s.start({ count: 3, rampSeconds: 0, profile: 'greedy', payProbability: 1 });
    await settle(s);
    expect(s.status().placed).toBeGreaterThan(3);
  });
});

describe('seat picking', () => {
  it('always yields 1-4 adjacent seats of one section', () => {
    for (let i = 0; i < 200; i++) {
      const p = pickSeats('burst', Math.random);
      expect(p.seatIds.length).toBeGreaterThanOrEqual(1);
      expect(p.seatIds.length).toBeLessThanOrEqual(4);
      for (const id of p.seatIds) expect(id).toMatch(new RegExp(`^${p.sectionId}:[ABC](0[1-9]|10)$`));
    }
  });
  it('greedy stays in PLATEA front rows', () => {
    for (let i = 0; i < 100; i++) {
      const p = pickSeats('greedy', Math.random);
      expect(p.sectionId).toMatch(/^PLATEA-/);
      expect(p.seatIds[0]).toMatch(/:[AB]/);
    }
  });
});

describe('HTTP API', () => {
  it('exposes healthz, start (202), status and stop', async () => {
    const { app, swarm } = buildApp({ bffUrl: 'http://bff', adminToken: 'tok', fetchImpl: fakeBff().f }, false);
    expect((await app.inject('/healthz')).json()).toEqual({ status: 'ok' });
    const bad = await app.inject({ method: 'POST', url: '/bots/start', payload: { count: 0 } });
    expect(bad.statusCode).toBe(400);
    const ok = await app.inject({ method: 'POST', url: '/bots/start', payload: { count: 200, rampSeconds: 5, profile: 'burst', payProbability: 0.85 } });
    expect(ok.statusCode).toBe(202);
    expect(Object.keys(ok.json()).sort()).toEqual(['active', 'confirmed', 'errors', 'placed', 'rejected', 'running']);
    expect((await app.inject({ method: 'POST', url: '/bots/start', payload: { count: 1 } })).statusCode).toBe(409);
    const stopped = await app.inject({ method: 'POST', url: '/bots/stop' });
    expect(stopped.json().running).toBe(false);
    expect((await app.inject('/bots/status')).statusCode).toBe(200);
    void swarm;
  });
});
