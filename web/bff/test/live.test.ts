import { afterEach, describe, expect, it } from 'vitest';
import type { AddressInfo } from 'node:net';
import { json, login, makeApp } from './helpers.js';
import { LeaderboardPoller } from '../src/live/leaderboard.js';
import { Upstream } from '../src/clients/upstream.js';

async function readFor(res: Response, ms: number): Promise<string> {
  const reader = res.body!.getReader();
  const dec = new TextDecoder();
  let out = '';
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const r = await Promise.race([reader.read(), new Promise<null>((ok) => setTimeout(() => ok(null), end - Date.now()))]);
    if (!r || r.done) break;
    out += dec.decode(r.value);
  }
  await reader.cancel().catch(() => undefined);
  return out;
}

describe('GET /live', () => {
  let close: (() => Promise<unknown>) | undefined;
  afterEach(async () => { await close?.(); close = undefined; });

  async function start(over = {}) {
    const m = await makeApp(() => json({}), { seatFlushMs: 20, ...over });
    m.hub.start();
    await m.app.listen({ port: 0, host: '127.0.0.1' });
    close = async () => { m.hub.stop(); await m.app.close(); };
    return { ...m, base: `http://127.0.0.1:${(m.app.server.address() as AddressInfo).port}` };
  }

  it('sends SSE headers, hello, and routes events end to end', async () => {
    const { base, hub, app } = await start();
    const { cookie, body } = await login(app);
    const res = await fetch(`${base}/live`, { headers: { cookie } });
    expect(res.headers.get('content-type')).toBe('text/event-stream');
    expect(res.headers.get('cache-control')).toBe('no-cache');
    expect(res.headers.get('x-accel-buffering')).toBe('no');
    setTimeout(() => {
      hub.handleUiEvent({ type: 'seat', seatId: 'S:A01', sectionId: 'S', state: 'HELD', version: 1 });
      hub.handleUiEvent({ type: 'order', buyerId: body.buyerId, orderId: 'o', status: 'HELD' });
      hub.handleUiEvent({ type: 'order', buyerId: 'someone-else', orderId: 'p', status: 'HELD' });
    }, 50);
    const text = await readFor(res, 300);
    expect(text).toContain('event: hello');
    expect(text).toContain(body.buyerId);
    expect(text).toContain('event: seat');
    expect(text.match(/event: me/g)).toHaveLength(1);
    expect(text).not.toContain('someone-else');
  });

  it('answers 503 above MAX_SSE_CONNECTIONS', async () => {
    const { base } = await start({ maxSseConnections: 1 });
    const first = await fetch(`${base}/live`);
    const second = await fetch(`${base}/live`);
    expect(second.status).toBe(503);
    await first.body!.cancel();
  });
});

describe('leaderboard poller', () => {
  it('polls only while a stage view is connected', async () => {
    const m = await makeApp(() => json([{ nickname: 'a', tickets: 2, fastestMs: 10 }]));
    const up = new Upstream(((...a: Parameters<typeof fetch>) => fetch(...a)) as typeof fetch, 100);
    let hits = 0;
    const fake = new Upstream((async () => { hits++; return json([{ nickname: 'a', tickets: 2, fastestMs: 10 }]); }) as typeof fetch, 100);
    void up;
    const poller = new LeaderboardPoller(m.cfg, fake, m.hub);
    await poller.tick();
    expect(hits).toBe(0);
    const written: string[] = [];
    m.hub.add({ id: 1, buyerId: null, view: 'stage', write: (c) => void written.push(c), queued: () => 0, close() {} });
    await poller.tick();
    expect(hits).toBe(1);
    expect(written[0]).toContain('event: leaderboard');
  });
});
