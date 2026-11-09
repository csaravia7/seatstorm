import { describe, expect, it } from 'vitest';
import { makeApp, login, json } from './helpers.js';
import { signSession, verifySession, validateNickname, newBuyerId, verifyAdmin, signAdmin } from '../src/session.js';

describe('session signing', () => {
  it('verifies a valid signature and rejects tampering', () => {
    const v = signSession({ b: 'b_12345678', n: 'marco', iat: 1 }, 's3cret');
    expect(verifySession(v, 's3cret')?.b).toBe('b_12345678');
    expect(verifySession(v, 'other')).toBeNull();
    const [payload, sig] = v.split('.');
    const forged = Buffer.from(JSON.stringify({ b: 'b_evil', n: 'marco', iat: 1 })).toString('base64url');
    expect(verifySession(`${forged}.${sig}`, 's3cret')).toBeNull();
    expect(verifySession(`${payload}.x`, 's3cret')).toBeNull();
    expect(verifySession('garbage', 's3cret')).toBeNull();
    expect(verifySession(undefined, 's3cret')).toBeNull();
  });

  it('generates buyer ids as b_ + 8 hex', () => {
    expect(newBuyerId()).toMatch(/^b_[0-9a-f]{8}$/);
  });

  it('expires admin cookies', () => {
    const v = signAdmin('k', 0);
    expect(verifyAdmin(v, 'k', 1000)).toBe(true);
    expect(verifyAdmin(v, 'k', 13 * 3600 * 1000)).toBe(false);
  });
});

describe('nickname validation', () => {
  it.each(['ab', 'a'.repeat(17), 'con espacio', 'ñandú', '', 'x<y'])('rejects %j', (n) => {
    expect(validateNickname(n).ok).toBe(false);
  });
  it.each(['marco', 'Neon_Fan-1', 'abc'])('accepts %j', (n) => {
    expect(validateNickname(n).ok).toBe(true);
  });
  it.each(['mierda99', 'f_u_c_k', 'Pendejo', 'sh1t', 'puta'])('filters bad word %j', (n) => {
    expect(validateNickname(n).ok).toBe(false);
  });
  it('does not flag innocent words', () => {
    expect(validateNickname('computo').ok).toBe(true);
    expect(validateNickname('classic').ok).toBe(true);
  });
});

describe('session routes', () => {
  it('creates a session cookie and /api/me reads it', async () => {
    const { app } = await makeApp();
    const { res, cookie, body } = await login(app);
    expect(res.statusCode).toBe(200);
    expect(body.nickname).toBe('marco');
    expect(body.buyerId).toMatch(/^b_[0-9a-f]{8}$/);
    const setCookie = res.cookies.find((c) => c.name === 'ss_sid')!;
    expect(setCookie.httpOnly).toBe(true);
    expect(setCookie.sameSite).toBe('Lax');
    expect(setCookie.secure).toBeFalsy();
    const me = await app.inject({ url: '/api/me', headers: { cookie } });
    expect(me.json()).toEqual(body);
  });

  it('marks the cookie Secure behind HTTPS', async () => {
    const { app } = await makeApp();
    const { res } = await login(app, 'marco', { 'x-forwarded-proto': 'https' });
    expect(res.cookies.find((c) => c.name === 'ss_sid')!.secure).toBe(true);
  });

  it('reuses the buyerId when a valid cookie is presented', async () => {
    const { app } = await makeApp();
    const first = await login(app);
    const res = await app.inject({ method: 'POST', url: '/api/session', payload: { nickname: 'nuevo' }, headers: { cookie: first.cookie } });
    expect(res.json().buyerId).toBe(first.body.buyerId);
  });

  it('rejects invalid nicknames with VALIDATION_ERROR', async () => {
    const { app } = await makeApp();
    for (const nickname of ['x', 'mierda']) {
      const res = await app.inject({ method: 'POST', url: '/api/session', payload: { nickname } });
      expect(res.statusCode).toBe(400);
      expect(res.json().code).toBe('VALIDATION_ERROR');
    }
  });

  it('answers 401 NO_SESSION for a tampered cookie', async () => {
    const { app } = await makeApp();
    const { cookie } = await login(app);
    const res = await app.inject({ url: '/api/me', headers: { cookie: cookie.slice(0, -2) + 'xx' } });
    expect(res.statusCode).toBe(401);
    expect(res.json().code).toBe('NO_SESSION');
  });

  it('serves /healthz and /api/config', async () => {
    const { app } = await makeApp(() => json({}), { kafkaUiUrl: 'http://k', dynatraceUrl: '' });
    expect((await app.inject('/healthz')).json()).toEqual({ status: 'ok' });
    expect((await app.inject('/api/config')).json()).toEqual({ kafkaUiUrl: 'http://k', dynatraceUrl: '' });
  });
});
