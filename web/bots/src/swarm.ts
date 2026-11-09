import { randomUUID } from 'node:crypto';
import { CONCERT_ID, pickSeats, type Profile, type Rng } from './layout.js';

export interface StartOptions {
  count: number;
  rampSeconds: number;
  profile: Profile;
  payProbability: number;
}

export interface Status {
  running: boolean;
  active: number;
  placed: number;
  confirmed: number;
  rejected: number;
  errors: number;
}

export interface SwarmDeps {
  bffUrl: string;
  adminToken: string;
  fetchImpl?: typeof fetch;
  /** Resolves after `ms`, or immediately when the signal aborts. */
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
  rng?: Rng;
  pollMs?: number;
  pollJitterMs?: number;
  pollMaxMs?: number;
  backoffBaseMs?: number;
}

export const defaultSleep = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    if (signal.aborted) return resolve();
    const t = setTimeout(done, ms);
    function done() {
      clearTimeout(t);
      signal.removeEventListener('abort', done);
      resolve();
    }
    signal.addEventListener('abort', done);
  });

const TERMINAL_BAD = new Set(['REJECTED', 'CANCELLED', 'EXPIRED']);
const MAX_RETRIES_429 = 3;

export class Swarm {
  private readonly f: typeof fetch;
  private readonly sleep: NonNullable<SwarmDeps['sleep']>;
  private readonly rng: Rng;
  private ac: AbortController | null = null;
  private pendingBots = 0;
  private counters = { active: 0, placed: 0, confirmed: 0, rejected: 0, errors: 0 };

  constructor(private readonly d: SwarmDeps) {
    this.f = d.fetchImpl ?? fetch;
    this.sleep = d.sleep ?? defaultSleep;
    this.rng = d.rng ?? Math.random;
  }

  status(): Status {
    return { running: this.ac !== null && !this.ac.signal.aborted && this.pendingBots > 0, ...this.counters };
  }

  /** Returns false if a run is already in progress. Counters reset on each new run. */
  start(o: StartOptions): boolean {
    if (this.status().running) return false;
    this.counters = { active: 0, placed: 0, confirmed: 0, rejected: 0, errors: 0 };
    const ac = (this.ac = new AbortController());
    this.pendingBots = o.count;
    for (let i = 0; i < o.count; i++) {
      const delay = this.startDelay(o, i);
      void this.bot(i + 1, delay, o, ac.signal).finally(() => {
        this.pendingBots--;
      });
    }
    return true;
  }

  stop(): Status {
    this.ac?.abort();
    return this.status();
  }

  private startDelay(o: StartOptions, i: number): number {
    const ramp = o.rampSeconds * 1000;
    if (o.profile === 'steady') return (i / Math.max(1, o.count)) * ramp + this.rng() * (ramp / Math.max(1, o.count));
    return this.rng() * ramp; // burst and greedy: everyone within the ramp window
  }

  private async bot(n: number, delayMs: number, o: StartOptions, signal: AbortSignal): Promise<void> {
    const c = this.counters;
    c.active++;
    try {
      await this.sleep(delayMs, signal);
      if (signal.aborted) return;
      const cookie = await this.createSession(n, signal);
      if (!cookie) {
        if (!signal.aborted) c.errors++;
        return;
      }
      await this.sleep(this.rng() * (o.profile === 'steady' ? 1500 : 500), signal); // think time
      const attempts = o.profile === 'greedy' ? 5 : 1;
      for (let a = 0; a < attempts && !signal.aborted; a++) {
        const outcome = await this.tryBuy(cookie, o, signal);
        if (outcome !== 'retry') return;
      }
    } catch (err) {
      if (!signal.aborted) c.errors++;
      void err;
    } finally {
      c.active--;
    }
  }

  private async createSession(n: number, signal: AbortSignal): Promise<string | null> {
    const res = await this.request('/api/session', { method: 'POST', body: { nickname: `bot-${String(n).padStart(4, '0')}` }, headers: { 'x-bot-token': this.d.adminToken } }, signal);
    if (!res || res.status !== 200) return null;
    const sc = res.headers.getSetCookie?.() ?? [];
    const sid = sc.map((s) => s.split(';')[0]!).find((s) => s.startsWith('ss_sid='));
    return sid ?? null;
  }

  /** One purchase attempt. Returns 'retry' when it was rejected and the profile wants another go. */
  private async tryBuy(cookie: string, o: StartOptions, signal: AbortSignal): Promise<'done' | 'retry'> {
    const c = this.counters;
    const pick = pickSeats(o.profile, this.rng);
    const res = await this.withBackoff(
      () =>
        this.request('/api/orders', {
          method: 'POST',
          body: { concertId: CONCERT_ID, ...pick },
          headers: { cookie, 'idempotency-key': randomUUID() },
        }, signal),
      signal,
    );
    if (!res) {
      if (!signal.aborted) c.errors++;
      return 'done';
    }
    if (res.status !== 202 && res.status !== 200) {
      c.errors++; // SALE_CLOSED, ACTIVE_ORDER_EXISTS, 429 after retries, 5xx...
      return 'done';
    }
    c.placed++;
    const { orderId } = (await res.json()) as { orderId: string };

    let deadline = Date.now() + (this.d.pollMaxMs ?? 15_000);
    let paid = false;
    while (!signal.aborted && Date.now() < deadline) {
      await this.sleep((this.d.pollMs ?? 1000) + (this.rng() * 2 - 1) * (this.d.pollJitterMs ?? 300), signal);
      if (signal.aborted) break;
      const r = await this.request(`/api/orders/${orderId}`, { headers: { cookie } }, signal);
      if (!r || r.status !== 200) continue;
      const { status } = (await r.json()) as { status: string };
      if (status === 'CONFIRMED') {
        c.confirmed++;
        return 'done';
      }
      if (TERMINAL_BAD.has(status)) {
        c.rejected++;
        return o.profile === 'greedy' ? 'retry' : 'done';
      }
      if (status === 'HELD' && !paid) {
        if (this.rng() >= o.payProbability) return 'done'; // lets the hold expire on purpose
        paid = true;
        const p = await this.withBackoff(
          () => this.request(`/api/orders/${orderId}/pay`, { method: 'POST', body: { method: 'CARD_SIM' }, headers: { cookie } }, signal),
          signal,
        );
        if (!p || p.status >= 300) {
          if (!signal.aborted) c.errors++;
          return 'done';
        }
        deadline = Date.now() + (this.d.pollMaxMs ?? 15_000);
      }
    }
    return 'done';
  }

  /** Retries on 429 with exponential backoff (max 3 retries). */
  private async withBackoff(call: () => Promise<Response | null>, signal: AbortSignal): Promise<Response | null> {
    let res = await call();
    for (let i = 0; i < MAX_RETRIES_429 && res?.status === 429 && !signal.aborted; i++) {
      await this.sleep((this.d.backoffBaseMs ?? 500) * 2 ** i + this.rng() * 100, signal);
      res = await call();
    }
    return res;
  }

  private async request(
    path: string,
    init: { method?: string; body?: unknown; headers?: Record<string, string> },
    signal: AbortSignal,
  ): Promise<Response | null> {
    try {
      return await this.f(`${this.d.bffUrl}${path}`, {
        method: init.method ?? 'GET',
        headers: { ...(init.body !== undefined ? { 'content-type': 'application/json' } : {}), ...init.headers },
        body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
        signal: AbortSignal.any([signal, AbortSignal.timeout(10_000)]),
      });
    } catch {
      return null;
    }
  }
}
