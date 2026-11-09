import { describe, expect, it } from 'vitest';
import type { AddressInfo } from 'node:net';
import { json, login, makeApp } from './helpers.js';

const sse = (chunks: string[]) =>
  new Response(
    new ReadableStream({
      start(c) {
        chunks.forEach((x) => c.enqueue(new TextEncoder().encode(x)));
        c.close();
      },
    }),
    { status: 200, headers: { 'content-type': 'text/event-stream' } },
  );

async function post(base: string, cookie: string, message: unknown) {
  return fetch(`${base}/api/chat`, { method: 'POST', headers: { cookie, 'content-type': 'application/json' }, body: JSON.stringify({ message }) });
}

describe('POST /api/chat', () => {
  async function start(handler: Parameters<typeof makeApp>[0]) {
    const m = await makeApp(handler);
    await m.app.listen({ port: 0, host: '127.0.0.1' });
    const login_ = await login(m.app);
    return { ...m, cookie: login_.cookie, base: `http://127.0.0.1:${(m.app.server.address() as AddressInfo).port}` };
  }

  it('relays the agent stream with buyer headers', async () => {
    const m = await start(() => sse(['event: token\ndata: Ho\n\n', 'event: token\ndata: la\n\n', 'event: done\ndata: \n\n']));
    const res = await post(m.base, m.cookie, 'hola');
    const text = await res.text();
    expect(res.headers.get('content-type')).toBe('text/event-stream');
    expect(text).toBe('event: token\ndata: Ho\n\nevent: token\ndata: la\n\nevent: done\ndata: \n\n');
    const h = m.calls[0]!.init.headers as Record<string, string>;
    expect(h['x-buyer-id']).toMatch(/^b_/);
    expect(h['x-session-id']).toHaveLength(16);
    await m.app.close();
  });

  it('emits an SSE error event with the fallback text when the agent is down', async () => {
    const m = await start(() => { throw new Error('ECONNREFUSED'); });
    const res = await post(m.base, m.cookie, 'hola');
    const text = await res.text();
    expect(res.status).toBe(200);
    expect(text).toContain('event: error');
    expect(text).toContain('Ahora mismo no puedo responder');
    await m.app.close();
  });

  it('validates the message and the session', async () => {
    const m = await start(() => sse([]));
    expect((await post(m.base, m.cookie, 'x'.repeat(501))).status).toBe(400);
    expect((await post(m.base, m.cookie, '')).status).toBe(400);
    expect((await post(m.base, '', 'hola')).status).toBe(401);
    await m.app.close();
  });

  it('limits to 6 messages per minute per buyer', async () => {
    const m = await start(() => sse(['event: done\ndata: \n\n']));
    const codes: number[] = [];
    for (let i = 0; i < 7; i++) {
      const r = await post(m.base, m.cookie, 'hola');
      codes.push(r.status);
      await r.text();
    }
    expect(codes).toEqual([200, 200, 200, 200, 200, 200, 429]);
    await m.app.close();
  });

  it('passes through agent 4xx errors', async () => {
    const m = await start(() => json({ code: 'RATE_LIMITED', message: 'x', details: {} }, 429));
    const r = await post(m.base, m.cookie, 'hola');
    expect(r.status).toBe(429);
    await m.app.close();
  });
});
