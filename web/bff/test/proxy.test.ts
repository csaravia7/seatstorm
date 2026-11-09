import { describe, expect, it } from 'vitest';
import { json, login, makeApp } from './helpers.js';

describe('REST proxy', () => {
  it('forwards order creation with buyer headers and Idempotency-Key', async () => {
    const { app, calls } = await makeApp(() => json({ orderId: 'o1', status: 'PLACED' }, 202));
    const { cookie, body } = await login(app);
    const res = await app.inject({
      method: 'POST',
      url: '/api/orders',
      headers: { cookie, 'idempotency-key': 'k-1' },
      payload: { concertId: 'neon-tide-2026', sectionId: 'PLATEA-A', seatIds: ['PLATEA-A:A01'] },
    });
    expect(res.statusCode).toBe(202);
    expect(res.json().orderId).toBe('o1');
    const h = calls[0]!.init.headers as Record<string, string>;
    expect(calls[0]!.url).toMatch(/\/api\/orders$/);
    expect(h['x-buyer-id']).toBe(body.buyerId);
    expect(h['x-buyer-nickname']).toBe('marco');
    expect(h['idempotency-key']).toBe('k-1');
    expect(JSON.parse(calls[0]!.init.body as string).sectionId).toBe('PLATEA-A');
  });

  it('propagates Idempotent-Replay and upstream error bodies', async () => {
    const { app } = await makeApp(() => json({ orderId: 'o1' }, 200, { 'idempotent-replay': 'true' }));
    const { cookie } = await login(app);
    const res = await app.inject({ method: 'POST', url: '/api/orders', headers: { cookie }, payload: {} });
    expect(res.headers['idempotent-replay']).toBe('true');

    const closed = await makeApp(() => json({ code: 'SALE_CLOSED', message: 'x', details: {} }, 409));
    const l = await login(closed.app);
    const r2 = await closed.app.inject({ method: 'POST', url: '/api/orders', headers: { cookie: l.cookie }, payload: {} });
    expect(r2.statusCode).toBe(409);
    expect(r2.json().code).toBe('SALE_CLOSED');
  });

  it('requires a session for order routes', async () => {
    const { app } = await makeApp();
    for (const [method, url] of [
      ['POST', '/api/orders'],
      ['POST', '/api/orders/x/pay'],
      ['POST', '/api/orders/x/cancel'],
      ['GET', '/api/orders/x'],
      ['GET', '/api/my/orders'],
    ] as const) {
      const res = await app.inject({ method, url, payload: method === 'POST' ? {} : undefined });
      expect(res.statusCode, url).toBe(401);
    }
  });

  it('maps my/orders to seatmap-query buyer listing', async () => {
    const { app, calls } = await makeApp(() => json([]));
    const { cookie, body } = await login(app);
    await app.inject({ url: '/api/my/orders', headers: { cookie } });
    expect(calls[0]!.url).toContain(`/api/buyers/${body.buyerId}/orders?limit=5`);
  });

  it('adds the default concertId on read endpoints', async () => {
    const { app, calls } = await makeApp(() => json({}));
    await app.inject('/api/seatmap');
    await app.inject('/api/stats?concertId=abc');
    expect(calls[0]!.url).toContain('/api/seatmap?concertId=neon-tide-2026');
    expect(calls[1]!.url).toContain('concertId=abc');
  });

  it('answers 503 UPSTREAM_UNAVAILABLE when a service is down', async () => {
    const { app } = await makeApp(() => {
      throw new TypeError('fetch failed');
    });
    const res = await app.inject('/api/concert');
    expect(res.statusCode).toBe(503);
    expect(res.json().code).toBe('UPSTREAM_UNAVAILABLE');
  });

  it('answers 503 within the timeout when a service hangs', async () => {
    const { app } = await makeApp(
      (_u, init) => new Promise((_r, rej) => init.signal!.addEventListener('abort', () => rej(new Error('aborted')))),
      { upstreamTimeoutMs: 50 },
    );
    const t = Date.now();
    const res = await app.inject('/api/concert');
    expect(res.statusCode).toBe(503);
    expect(Date.now() - t).toBeLessThan(2000);
  });
});

describe('rate limits', () => {
  it('limits POST /api/orders to 3 per 10 s per buyer', async () => {
    const { app } = await makeApp(() => json({ orderId: 'o' }, 202));
    const { cookie } = await login(app);
    const codes: number[] = [];
    for (let i = 0; i < 4; i++) {
      const r = await app.inject({ method: 'POST', url: '/api/orders', headers: { cookie }, payload: {} });
      codes.push(r.statusCode);
      if (i === 3) expect(r.json().code).toBe('RATE_LIMITED');
    }
    expect(codes).toEqual([202, 202, 202, 429]);
    // another buyer is unaffected
    const other = await login(app, 'otro');
    const r = await app.inject({ method: 'POST', url: '/api/orders', headers: { cookie: other.cookie }, payload: {} });
    expect(r.statusCode).toBe(202);
  });

  it('does not block 100 sessions from the same IP in a minute', async () => {
    const { app } = await makeApp();
    for (let i = 0; i < 100; i++) {
      const r = await app.inject({ method: 'POST', url: '/api/session', payload: { nickname: `user${i}` }, remoteAddress: '10.0.0.9' });
      expect(r.statusCode).toBe(200);
    }
  });

  it('limits sessions to 300 per minute per IP, except for bot-token clients', async () => {
    const { app } = await makeApp();
    let last = 0;
    for (let i = 0; i < 301; i++) {
      last = (await app.inject({ method: 'POST', url: '/api/session', payload: { nickname: 'abc' }, remoteAddress: '10.0.0.8' })).statusCode;
    }
    expect(last).toBe(429);
    const bot = await app.inject({
      method: 'POST', url: '/api/session', payload: { nickname: 'bot-0001' }, remoteAddress: '10.0.0.8', headers: { 'x-bot-token': 'tok' },
    });
    expect(bot.statusCode).toBe(200);
  });

  it('applies the general 20 req/s limit per buyer', async () => {
    const { app } = await makeApp(() => json({}));
    const { cookie } = await login(app);
    const codes: number[] = [];
    for (let i = 0; i < 25; i++) codes.push((await app.inject({ url: '/api/me', headers: { cookie } })).statusCode);
    expect(codes.filter((c) => c === 429).length).toBeGreaterThan(0);
  });
});

describe('admin', () => {
  it('rejects a wrong PIN and issues ss_admin for the right one', async () => {
    const { app } = await makeApp();
    const bad = await app.inject({ method: 'POST', url: '/admin/login', payload: { pin: '0000' } });
    expect(bad.statusCode).toBe(401);
    const ok = await app.inject({ method: 'POST', url: '/admin/login', payload: { pin: '1234' } });
    expect(ok.statusCode).toBe(200);
    expect(ok.cookies.find((c) => c.name === 'ss_admin')).toBeTruthy();
  });

  it('guards /admin/* and forwards with X-Admin-Token', async () => {
    const { app, calls } = await makeApp(() => json({ ok: true }, 202));
    for (const [method, url] of [
      ['POST', '/admin/sale/open'], ['PUT', '/admin/payment'], ['POST', '/admin/reset'],
      ['GET', '/admin/reset/r1'], ['POST', '/admin/bots/start'], ['GET', '/admin/bots/status'],
    ] as const) {
      expect((await app.inject({ method, url, payload: method === 'GET' ? undefined : {} })).statusCode, url).toBe(401);
    }
    const login = await app.inject({ method: 'POST', url: '/admin/login', payload: { pin: '1234' } });
    const cookie = `ss_admin=${login.cookies[0]!.value}`;
    const go = (method: 'POST' | 'PUT' | 'GET', url: string, payload?: object) =>
      app.inject({ method, url, headers: { cookie }, payload });
    await go('POST', '/admin/sale/open');
    await go('PUT', '/admin/payment', { mode: 'PAUSED' });
    await go('POST', '/admin/reset');
    await go('GET', '/admin/reset/r1');
    await go('POST', '/admin/bots/start', { count: 5 });
    await go('GET', '/admin/bots/status');
    expect(calls.map((c) => c.url)).toEqual([
      'http://localhost:8106/admin/sale/open',
      'http://localhost:8103/admin/mode',
      'http://localhost:8106/admin/reset',
      'http://localhost:8106/admin/reset/r1',
      'http://localhost:3100/bots/start',
      'http://localhost:3100/bots/status',
    ]);
    for (const c of calls) expect((c.init.headers as Record<string, string>)['x-admin-token']).toBe('tok');
    expect(calls[1]!.init.method).toBe('PUT');
  });

  it('does not accept a buyer cookie as admin', async () => {
    const { app } = await makeApp();
    const { cookie } = await login(app);
    expect((await app.inject({ url: '/admin/me', headers: { cookie } })).statusCode).toBe(401);
  });
});
